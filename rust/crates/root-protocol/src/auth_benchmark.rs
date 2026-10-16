//! Explicit synthetic comparison before changing authenticated production packets.
use super::{hmac_sha256, Digest, Sha256};

struct Prepared {
    inner: Sha256,
    outer: Sha256,
}
impl Prepared {
    fn new(key: &[u8; 32]) -> Self {
        let mut inner_pad = [0x36; 64];
        let mut outer_pad = [0x5c; 64];
        for (index, byte) in key.iter().enumerate() {
            inner_pad[index] ^= byte;
            outer_pad[index] ^= byte;
        }
        let mut inner = Sha256::new();
        inner.update(inner_pad);
        let mut outer = Sha256::new();
        outer.update(outer_pad);
        Self { inner, outer }
    }
    fn sign(&self, message: &[u8]) -> [u8; 32] {
        let mut inner = self.inner.clone();
        inner.update(message);
        let mut outer = self.outer.clone();
        outer.update(inner.finalize());
        outer.finalize().into()
    }
}

#[test]
#[ignore = "synthetic HMAC preparation comparison, not Android capture"]
fn benchmark_prepared_capture_authentication() {
    let key = [74; 32];
    let prepared = Prepared::new(&key);
    let packet = vec![37; 60 * 1024 + 37];
    assert_eq!(hmac_sha256(&key, &packet), prepared.sign(&packet));
    let mut times = [Vec::new(), Vec::new()];
    for iteration in 0..110 {
        for variant in if iteration % 2 == 0 { [0, 1] } else { [1, 0] } {
            let start = std::time::Instant::now();
            for _ in 0..60 {
                let tag = if variant == 0 {
                    hmac_sha256(&key, std::hint::black_box(&packet))
                } else {
                    prepared.sign(std::hint::black_box(&packet))
                };
                std::hint::black_box(tag);
            }
            if iteration >= 10 {
                times[variant].push(start.elapsed());
            }
        }
    }
    for (name, mut values) in ["original", "prepared"].into_iter().zip(times) {
        values.sort_unstable();
        println!("capture-auth-{name}: host={}, N=100, 60 packets x (60KiB+37), HMAC-SHA256, P50={:.6} P95={:.6} P99={:.6} ms",
            std::env::consts::ARCH, values[49].as_secs_f64() * 1000.0, values[94].as_secs_f64() * 1000.0, values[98].as_secs_f64() * 1000.0);
    }
}

#[test]
#[ignore = "CPU-only fixed-byte authenticated packet geometry audit; no wire change"]
fn benchmark_capture_authentication_geometry() {
    #[cfg(target_arch = "x86_64")]
    println!(
        "host={} sha-instructions={}",
        std::env::consts::ARCH,
        std::is_x86_feature_detected!("sha")
    );
    #[cfg(target_arch = "aarch64")]
    println!(
        "host={} sha-instructions={}",
        std::env::consts::ARCH,
        std::arch::is_aarch64_feature_detected!("sha2")
    );
    let pixels = vec![37; 720 * 1280 * 4];
    let key = [74; 32];
    let sizes = [16 * 1024, 32 * 1024, 60 * 1024];
    let mut times = [Vec::new(), Vec::new(), Vec::new()];
    for iteration in 0..110 {
        // Rotate order to avoid attributing a warm CPU to one packet size.
        for offset in 0..3 {
            let variant = (iteration + offset) % 3;
            let start = std::time::Instant::now();
            for _ in 0..5 {
                for chunk in pixels.chunks(sizes[variant]) {
                    std::hint::black_box(hmac_sha256(&key, std::hint::black_box(chunk)));
                }
            }
            if iteration >= 10 {
                times[variant].push(start.elapsed().as_secs_f64() * 200.0);
            }
        }
    }
    for (size, mut values) in sizes.into_iter().zip(times) {
        values.sort_by(f64::total_cmp);
        println!("payloadChunk={size} packets={} HMAC-only-per-frame P50={:.6} P95={:.6} P99={:.6}ms; frame=720x1280x4 N=100, excludes headers/Socket/readback", pixels.len().div_ceil(size), values[49], values[94], values[98]);
    }
}
