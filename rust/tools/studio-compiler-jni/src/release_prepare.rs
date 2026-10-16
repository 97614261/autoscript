//! Device release freezing remains Studio-only and reuses the audited host packager.
use release_packager::{PrepareOptions, ReleaseError};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

static CANCELLED: OnceLock<Mutex<VecDeque<i64>>> = OnceLock::new();
static BUSY: AtomicBool = AtomicBool::new(false);

struct WorkGuard;
impl Drop for WorkGuard {
    fn drop(&mut self) {
        BUSY.store(false, Ordering::Release);
    }
}

pub(crate) fn cancel(id: i64) {
    if id <= 0 {
        return;
    }
    let mut ids = CANCELLED
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if !ids.contains(&id) {
        ids.push_back(id);
    }
    while ids.len() > 64 {
        ids.pop_front();
    }
}

pub(crate) fn prepare(id: i64, project: &str, output: &str) -> String {
    if BUSY
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return serde_json::json!({"status":"failed", "message":"device release preparation is busy"}).to_string();
    }
    let _work = WorkGuard;
    let start = Instant::now();
    let result = (|| {
        if id <= 0
            || project.len() > 4096
            || output.len() > 4096
            || project.contains('\0')
            || output.contains('\0')
        {
            return Err(ReleaseError::InvalidOption(
                "invalid device release request".into(),
            ));
        }
        release_packager::prepare_project_with_checkpoint(
            std::path::Path::new(project),
            std::path::Path::new(output),
            &PrepareOptions {
                application_id: "com.zhigou.yunxingban".into(),
                version_code: 1,
                version_name: "0.1.0".into(),
            },
            || {
                let cancelled = CANCELLED
                    .get_or_init(|| Mutex::new(VecDeque::new()))
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .contains(&id);
                if cancelled || start.elapsed() >= Duration::from_mins(2) {
                    Err(ReleaseError::Cancelled)
                } else {
                    Ok(())
                }
            },
        )
    })();
    CANCELLED
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .retain(|value| *value != id);
    match result {
        Ok(manifest) => serde_json::json!({"status":"success", "release":manifest}).to_string(),
        Err(error) => {
            serde_json::json!({"status":"failed", "message":error.to_string()}).to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn early_cancel_is_observed_before_accessing_project_or_creating_output() {
        super::cancel(9_238_171);
        let reply: serde_json::Value = serde_json::from_str(&super::prepare(
            9_238_171,
            "missing-project",
            "missing-output",
        ))
        .unwrap();
        assert_eq!(reply["status"], "failed");
        assert_eq!(reply["message"], "Cancelled");
    }
}
