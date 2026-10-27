use root_protocol::StatusCode;

/// The caller retains its uncertain pointer until both events are acknowledged.
/// A rejected/timed-out event is never replayed; priority cleanup owns release.
pub fn inject_tap(
    mut cancelled: impl FnMut() -> bool,
    mut inject: impl FnMut(i32) -> StatusCode,
) -> StatusCode {
    for action in [0, 1] {
        if cancelled() {
            return StatusCode::Cancelled;
        }
        let status = inject(action);
        if status != StatusCode::Ok {
            return status;
        }
    }
    StatusCode::Ok
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tap_acknowledges_down_then_up_once() {
        let mut events = Vec::new();
        assert_eq!(
            inject_tap(
                || false,
                |a| {
                    events.push(a);
                    StatusCode::Ok
                }
            ),
            StatusCode::Ok
        );
        assert_eq!(events, [0, 1]);
    }
    #[test]
    fn neither_failed_event_is_replayed() {
        for failed in [0, 1] {
            let mut events = Vec::new();
            assert_eq!(
                inject_tap(
                    || false,
                    |a| {
                        events.push(a);
                        if a == failed {
                            StatusCode::BackendFailure
                        } else {
                            StatusCode::Ok
                        }
                    }
                ),
                StatusCode::BackendFailure
            );
            assert_eq!(events, if failed == 0 { vec![0] } else { vec![0, 1] });
        }
    }
    #[test]
    fn cancellation_before_either_event_leaves_release_to_priority_cleanup() {
        for cutoff in [0, 1] {
            let mut checks = 0;
            let mut events = Vec::new();
            let status = inject_tap(
                || {
                    let cancelled = checks == cutoff;
                    checks += 1;
                    cancelled
                },
                |a| {
                    events.push(a);
                    StatusCode::Ok
                },
            );
            assert_eq!(status, StatusCode::Cancelled);
            assert_eq!(events, if cutoff == 0 { vec![] } else { vec![0] });
        }
    }
}
