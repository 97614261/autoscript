//! A live Android boot clock, not the scheduler pump's frozen timestamp.

#[cfg(any(target_os = "android", test))]
fn checked_millis(seconds: i64, nanoseconds: i64) -> Option<u64> {
    if !(0..1_000_000_000).contains(&nanoseconds) {
        return None;
    }
    u64::try_from(seconds)
        .ok()?
        .checked_mul(1_000)?
        .checked_add(u64::try_from(nanoseconds).ok()? / 1_000_000)
}

#[cfg(target_os = "android")]
pub(super) fn elapsed_millis() -> Option<u64> {
    let mut value = std::mem::MaybeUninit::<libc::timespec>::uninit();
    // SAFETY: clock_gettime initializes the entire timespec on success. The pointer is valid
    // and exclusively owned; a failed call never exposes uninitialized bytes.
    if unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, value.as_mut_ptr()) } != 0 {
        return None;
    }
    // SAFETY: success above initialized value.
    let value = unsafe { value.assume_init() };
    checked_millis(value.tv_sec, value.tv_nsec)
}

#[cfg(test)]
mod tests {
    use super::checked_millis;

    #[test]
    fn boot_clock_preserves_epoch_and_subsecond_precision_without_overflow() {
        assert_eq!(checked_millis(123, 456_789_012), Some(123_456));
        assert_eq!(checked_millis(123, 466_789_012), Some(123_466));
        assert_eq!(checked_millis(-1, 0), None);
        assert_eq!(checked_millis(1, -1), None);
        assert_eq!(checked_millis(1, 1_000_000_000), None);
        assert_eq!(checked_millis(i64::MAX, 0), None);
    }
}
