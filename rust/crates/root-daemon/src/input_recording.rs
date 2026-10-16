//! Android-only evdev acquisition. Fixed /dev/input/eventN files, no client-supplied paths or shell.
use root_daemon_core::recording::{Axis, InputEvent, RecordingDecoder};
use root_protocol::recording::{RecordingConfig, RecordingFinish, RecordingResult};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileTypeExt, OpenOptionsExt};
use std::os::unix::net::UnixStream;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

struct Device {
    file: File,
    touch: bool,
}
struct Session {
    token: u64,
    signal: Arc<AtomicU32>,
    wake: UnixStream,
    result: mpsc::Receiver<RecordingResult>,
    worker: std::thread::JoinHandle<()>,
}
#[derive(Default)]
pub(crate) struct RecordingHub {
    active: Mutex<Option<Session>>,
}
impl RecordingHub {
    pub fn active(&self) -> bool {
        self.active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .is_some()
    }
    pub fn start(&self, config: RecordingConfig) -> Result<(), u32> {
        config.validate().map_err(|_| 7u32)?;
        let mut active = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if active.is_some() {
            return Err(10);
        }
        let (devices, x, y) = devices().map_err(|_| 1u32)?;
        let started_us = monotonic_us().map_err(|_| 11u32)?;
        let decoder = RecordingDecoder::new(config, x, y, started_us).map_err(|_| 1u32)?;
        let (wake, read_wake) = UnixStream::pair().map_err(|_| 11u32)?;
        wake.set_nonblocking(true).map_err(|_| 11u32)?;
        let signal = Arc::new(AtomicU32::new(0));
        let worker_signal = signal.clone();
        let (send, result) = mpsc::sync_channel(1);
        let worker = std::thread::Builder::new()
            .name("root-input-recording".into())
            .spawn(move || {
                let result = read_events(
                    devices,
                    read_wake,
                    worker_signal,
                    config,
                    decoder,
                    started_us,
                );
                let _ = send.send(result);
            })
            .map_err(|_| 11u32)?;
        *active = Some(Session {
            token: config.token,
            signal,
            wake,
            result,
            worker,
        });
        Ok(())
    }
    pub fn finish(&self, finish: RecordingFinish) -> Result<RecordingResult, u32> {
        // Hold the lifecycle gate through join; a second start cannot race stop cleanup.
        let mut active = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let session = {
            if active.as_ref().map(|session| session.token) != Some(finish.token) {
                return Err(9);
            }
            active.take().ok_or(9u32)?
        };
        session.signal.store(
            1 | u32::from(finish.cancel) << 1 | u32::from(finish.discard_current_touch) << 2,
            Ordering::Release,
        );
        let _ = (&session.wake).write(&[1]);
        let result = session
            .result
            .recv_timeout(Duration::from_secs(2))
            .map_err(|_| 11u32)?;
        let _ = session.worker.join();
        if finish.cancel {
            Ok(RecordingResult {
                token: finish.token,
                elapsed_ms: result.elapsed_ms,
                status: 4,
                skipped_gestures: 0,
                actions: Vec::new(),
            })
        } else {
            Ok(result)
        }
    }
    pub fn cancel_all(&self) {
        let token = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
            .map(|session| session.token);
        if let Some(token) = token {
            let _ = self.finish(RecordingFinish {
                token,
                cancel: true,
                discard_current_touch: true,
            });
        }
    }
}

fn devices() -> io::Result<(Vec<Device>, Axis, Axis)> {
    let mut output = Vec::new();
    let mut touch_axes = None;
    for entry in fs::read_dir("/dev/input")?.take(128) {
        let entry = entry?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        if !name.strip_prefix("event").is_some_and(|suffix| {
            !suffix.is_empty()
                && suffix.len() <= 3
                && suffix.bytes().all(|byte| byte.is_ascii_digit())
        }) {
            continue;
        }
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.file_type().is_char_device() || metadata.file_type().is_symlink() {
            continue;
        }
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(entry.path())?;
        let abs = bits(&file, 3, 8)?;
        let key = bits(&file, 1, 96)?;
        let touch = has(&abs, 53) || has(&abs, 54);
        if touch {
            if touch_axes.is_some() || ![47, 53, 54, 57].iter().all(|code| has(&abs, *code)) {
                return Err(io::Error::other("unsupported touchscreen"));
            }
            let slot = axis(&file, 47)?;
            if slot.minimum != 0 || !(0..32).contains(&slot.maximum) {
                return Err(io::Error::other("unsupported slots"));
            }
            let mut tracking = [0i32; 33];
            tracking[0] = 57;
            let length = (usize::try_from(slot.maximum).unwrap_or(32) + 2) * 4;
            // SAFETY: kernel writes at most the encoded byte length into this owned, aligned array.
            let result = unsafe {
                libc::ioctl(
                    file.as_raw_fd(),
                    ioc(2, 0x0a, length),
                    tracking.as_mut_ptr(),
                )
            };
            if result < 0 || tracking[1..length / 4].iter().any(|id| *id >= 0) {
                return Err(io::Error::other("touchscreen is not idle"));
            }
            touch_axes = Some((axis(&file, 53)?, axis(&file, 54)?));
        }
        if touch
            || [1, 28, 102, 114, 115, 139, 158]
                .iter()
                .any(|code| has(&key, *code))
        {
            let clock = libc::CLOCK_MONOTONIC;
            // SAFETY: EVIOCSCLOCKID reads exactly an int from this valid pointer.
            if unsafe { libc::ioctl(file.as_raw_fd(), ioc(1, 0xa0, 4), std::ptr::addr_of!(clock)) }
                < 0
            {
                return Err(io::Error::last_os_error());
            }
            output.push(Device { file, touch });
            if output.len() > 16 {
                return Err(io::Error::other("device budget"));
            }
        }
    }
    let (x, y) = touch_axes.ok_or_else(|| io::Error::other("no supported touchscreen"))?;
    Ok((output, x, y))
}
fn ioc(direction: u64, command: u64, length: usize) -> libc::c_int {
    // Android bionic declares ioctl's request as int; preserve the unsigned _IOC bit pattern.
    ((direction << 30) | ((length as u64) << 16) | (u64::from(b'E') << 8) | command) as libc::c_int
}
fn bits(file: &File, event: u64, length: usize) -> io::Result<Vec<u8>> {
    let mut bytes = vec![0u8; length];
    // SAFETY: the ioctl request bounds the kernel write to this initialized byte array.
    if unsafe {
        libc::ioctl(
            file.as_raw_fd(),
            ioc(2, 0x20 + event, length),
            bytes.as_mut_ptr(),
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(bytes)
}
fn has(bytes: &[u8], bit: usize) -> bool {
    bytes
        .get(bit / 8)
        .is_some_and(|byte| byte & (1u8 << (bit % 8)) != 0)
}
fn axis(file: &File, code: u64) -> io::Result<Axis> {
    let mut values = [0i32; 6];
    // SAFETY: input_absinfo contains six ints; the encoded ioctl size and backing array agree.
    if unsafe {
        libc::ioctl(
            file.as_raw_fd(),
            ioc(2, 0x40 + code, 24),
            values.as_mut_ptr(),
        )
    } < 0
    {
        return Err(io::Error::last_os_error());
    }
    if values[2] < values[1] || (code != 47 && values[2] == values[1]) {
        return Err(io::Error::other("invalid axis range"));
    }
    Ok(Axis {
        minimum: values[1],
        maximum: values[2],
    })
}
fn monotonic_us() -> io::Result<u64> {
    let mut time = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: clock_gettime initializes the owned timespec; no pointer is retained.
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, std::ptr::addr_of_mut!(time)) } < 0 {
        return Err(io::Error::last_os_error());
    }
    u64::try_from(time.tv_sec)
        .ok()
        .and_then(|seconds| seconds.checked_mul(1000000))
        .and_then(|base| {
            u64::try_from(time.tv_nsec)
                .ok()
                .and_then(|nano| base.checked_add(nano / 1000))
        })
        .ok_or_else(|| io::Error::other("clock overflow"))
}
fn read_events(
    mut devices: Vec<Device>,
    wake: UnixStream,
    signal: Arc<AtomicU32>,
    config: RecordingConfig,
    mut decoder: RecordingDecoder,
    started_us: u64,
) -> RecordingResult {
    let clock = Instant::now();
    let deadline = clock + Duration::from_millis(u64::from(config.max_duration_ms));
    let mut descriptors = devices
        .iter()
        .map(|device| libc::pollfd {
            fd: device.file.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        })
        .collect::<Vec<_>>();
    descriptors.push(libc::pollfd {
        fd: wake.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    });
    loop {
        let now = Instant::now();
        if now >= deadline {
            if !config.deadline_success {
                decoder.fail(5);
            }
            break;
        }
        let timeout = i32::try_from((deadline - now).as_millis())
            .unwrap_or(60000)
            .max(1);
        // SAFETY: poll borrows live device/wake fds and an initialized descriptor array for this call.
        let ready = unsafe {
            libc::poll(
                descriptors.as_mut_ptr(),
                descriptors.len() as libc::nfds_t,
                timeout,
            )
        };
        if ready < 0 {
            if io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            decoder.fail(7);
            break;
        }
        if ready == 0 {
            decoder.fail(5);
            break;
        }
        let mut events = Vec::with_capacity(128);
        for (index, device) in devices.iter_mut().enumerate() {
            if descriptors[index].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                decoder.fail(7);
                break;
            }
            if descriptors[index].revents & libc::POLLIN == 0 {
                continue;
            }
            loop {
                let mut bytes = [0u8; 24];
                match device.file.read(&mut bytes) {
                    Ok(24) => {
                        let seconds =
                            i64::from_le_bytes(bytes[0..8].try_into().expect("fixed input event"));
                        let micro =
                            i64::from_le_bytes(bytes[8..16].try_into().expect("fixed input event"));
                        let Some(at_us) = u64::try_from(seconds)
                            .ok()
                            .and_then(|seconds| seconds.checked_mul(1000000))
                            .and_then(|base| {
                                u64::try_from(micro)
                                    .ok()
                                    .filter(|micro| *micro < 1000000)
                                    .and_then(|micro| base.checked_add(micro))
                            })
                        else {
                            decoder.fail(7);
                            break;
                        };
                        if at_us < started_us {
                            continue;
                        }
                        events.push((
                            InputEvent {
                                at_us,
                                event_type: u16::from_le_bytes(
                                    bytes[16..18].try_into().expect("fixed input event"),
                                ),
                                code: u16::from_le_bytes(
                                    bytes[18..20].try_into().expect("fixed input event"),
                                ),
                                value: i32::from_le_bytes(
                                    bytes[20..24].try_into().expect("fixed input event"),
                                ),
                            },
                            device.touch,
                        ));
                        if events.len() > config.event_budget as usize {
                            decoder.fail(3);
                            break;
                        }
                    }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                    Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                    _ => {
                        decoder.fail(7);
                        break;
                    }
                }
            }
            if decoder.status() != 0 {
                break;
            }
        }
        events.sort_by_key(|(event, _)| event.at_us);
        for (event, touch) in events {
            decoder.event(event, touch);
        }
        if decoder.status() != 0 || signal.load(Ordering::Acquire) & 1 != 0 {
            break;
        }
    }
    let flags = signal.load(Ordering::Acquire);
    let elapsed = monotonic_us()
        .ok()
        .and_then(|now| u32::try_from(now.saturating_sub(started_us) / 1000).ok())
        .unwrap_or(config.max_duration_ms)
        .min(config.max_duration_ms);
    decoder.finish(
        elapsed,
        flags & 2 != 0,
        flags & 4 != 0 || config.deadline_success,
    )
}
