//! Bounded interactive UI arguments and exact choice results.
use lua_runtime::LuaScalar;

pub(crate) fn text(value: &LuaScalar, maximum: usize) -> Result<&str, &'static str> {
    match value {
        LuaScalar::Bytes(bytes)
            if !bytes.is_empty() && bytes.len() <= maximum && !bytes.contains(&0) =>
        {
            std::str::from_utf8(bytes).map_err(|_| "invalid dialog UTF-8")
        }
        _ => Err("invalid dialog text"),
    }
}
pub(crate) fn timeout(opcode: u32, args: &[LuaScalar]) -> Result<u64, &'static str> {
    match args.get(if opcode == 1204 { 4 } else { 2 }) {
        Some(LuaScalar::Integer(value)) if (1000..=1_800_000).contains(value) => {
            u64::try_from(*value).map_err(|_| "invalid dialog timeout")
        }
        _ => Err("invalid dialog timeout"),
    }
}
pub(crate) fn validate(opcode: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    match opcode {
        1204 if args.len() == 5 => {
            for (arg, bound) in args[..4].iter().zip([192, 4096, 96, 96]) {
                text(arg, bound)?;
            }
            timeout(opcode, args)?;
        }
        1205 if args.len() == 3 => {
            let id = text(&args[0], 64)?;
            if !id
                .bytes()
                .enumerate()
                .all(|(i, b)| b == b'_' || b.is_ascii_alphabetic() || i > 0 && b.is_ascii_digit())
                || !matches!(args[1], LuaScalar::Boolean(_))
            {
                return Err("invalid dialog page");
            }
            timeout(opcode, args)?;
        }
        1206 if args.is_empty() => (),
        _ => return Err("invalid dialog arguments"),
    }
    Ok(())
}
pub(crate) fn valid_result(opcode: u32, payload: &[u8]) -> bool {
    matches!(payload, b"dismiss" | b"timeout")
        || if opcode == 1204 {
            matches!(payload, b"confirm" | b"cancel")
        } else {
            payload == b"close"
        }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn arguments_are_bounded_and_dismiss_is_never_confirm() {
        let args = vec![
            LuaScalar::Bytes(b"main".to_vec()),
            LuaScalar::Boolean(true),
            LuaScalar::Integer(60_000),
        ];
        assert!(validate(1205, &args).is_ok());
        assert!(validate(1206, &[]).is_ok());
        assert!(validate(1206, &args).is_err());
        for value in [999, 1_800_001] {
            let mut bad = args.clone();
            bad[2] = LuaScalar::Integer(value);
            assert!(validate(1205, &bad).is_err());
        }
        assert!(!valid_result(1204, b"true"));
        assert!(!valid_result(1205, b"confirm"));
        assert!(valid_result(1204, b"dismiss"));
    }

    fn runtime(
        clock: &runtime_scheduler::ManualClock,
        queue: &crate::ExternalHostQueue,
    ) -> crate::RuntimeExecutor<runtime_scheduler::ManualClock, crate::ExternalHost> {
        crate::RuntimeExecutor::new(
            clock.clone(),
            runtime_scheduler::SchedulerConfig::default(),
            lua_runtime::LuaRuntimeConfig::default(),
            crate::ExecutorConfig::default(),
            crate::ExternalHost::new(queue.clone()),
        )
        .unwrap()
    }

    #[test]
    fn confirm_waits_then_returns_exact_choice_without_blocking_other_lua_tasks() {
        use crate::{ExternalHostEvent, ExternalHostQueue};
        use runtime_scheduler::{HostCompletion, HostResult, ManualClock, MonoTime, TaskState};
        use std::time::Duration;
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(8, 8).unwrap();
        let mut rt = runtime(&clock, &queue);
        let owner=rt.start_entry_chunk(b"return function() Task.spawn(function() other=true; UI.closeDialog() end); answer=UI.confirm('T','M','OK','Cancel',1000); done=true end","confirm").unwrap();
        rt.drive().unwrap();
        assert_eq!(rt.lua().global_scalar("done").unwrap(), LuaScalar::Nil);
        assert_eq!(
            rt.lua().global_scalar("other").unwrap(),
            LuaScalar::Boolean(true)
        );
        assert!(rt
            .drain_script_prompts()
            .iter()
            .any(|s| s.contains("dialogClose")));
        let Some(ExternalHostEvent::Dispatch {
            request,
            completion,
        }) = queue.wait_next_timeout(Duration::from_millis(10))
        else {
            panic!("missing dialog dispatch")
        };
        assert_eq!(request.opcode, 1204);
        assert_eq!(request.timeout, Duration::from_secs(4));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"cancel".to_vec()),
            })
            .unwrap();
        rt.drive().unwrap();
        assert_eq!(rt.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            rt.lua().global_scalar("answer").unwrap(),
            LuaScalar::Bytes(b"cancel".to_vec())
        );
    }

    #[test]
    fn stopped_dialog_discards_late_confirmation_and_expires_with_cancel() {
        use crate::{ExternalHostEvent, ExternalHostQueue};
        use runtime_scheduler::{HostCompletion, HostResult, ManualClock, MonoTime, TaskState};
        use std::time::Duration;
        for stop in [true, false] {
            let clock = ManualClock::new(MonoTime::ZERO);
            let queue = ExternalHostQueue::new(8, 8).unwrap();
            let mut rt = runtime(&clock, &queue);
            let owner=rt.start_entry_chunk(b"return function() answer=UI.showDialogAndWait('main',true,1000); done=true end","layout").unwrap();
            rt.drive().unwrap();
            let Some(ExternalHostEvent::Dispatch {
                request,
                completion,
            }) = queue.wait_next_timeout(Duration::from_millis(10))
            else {
                panic!("missing layout dispatch")
            };
            if stop {
                rt.handle().request_cancel(owner).unwrap();
            } else {
                clock.advance(Duration::from_millis(4001));
            }
            rt.drive().unwrap();
            assert_eq!(
                rt.task_state(owner),
                Some(if stop {
                    TaskState::Cancelled
                } else {
                    TaskState::Failed
                })
            );
            assert!(matches!(
                queue.wait_next_timeout(Duration::from_millis(10)),
                Some(ExternalHostEvent::Cancel { .. })
            ));
            completion
                .submit_host_completion(HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result: HostResult::Success(b"close".to_vec()),
                })
                .unwrap();
            rt.drive().unwrap();
            assert_eq!(rt.lua().global_scalar("done").unwrap(), LuaScalar::Nil);
        }
    }

    #[test]
    fn dialogs_require_capability_and_reject_injected_payloads() {
        use runtime_scheduler::{ManualClock, MonoTime};
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = crate::ExternalHostQueue::new(4, 4).unwrap();
        let mut rt = runtime(&clock, &queue);
        rt.set_allowed_capabilities(&["core.task".into()]).unwrap();
        rt.start_entry_chunk(
            b"return function() allowed=pcall(UI.confirm,'T','M','OK','Cancel',1000) end",
            "denied",
        )
        .unwrap();
        rt.drive().unwrap();
        assert_eq!(
            rt.lua().global_scalar("allowed").unwrap(),
            LuaScalar::Boolean(false)
        );
        assert!(crate::host_resume_input(
            1204,
            &runtime_scheduler::HostResult::Success(b"yes".to_vec())
        )
        .is_err());
    }
}
