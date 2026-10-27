//! Task-bound compiled patterns only: never source strings or match results.
use runtime_scheduler::TaskToken;
use std::collections::VecDeque;
use std::sync::Arc;

pub(crate) type Compiled = Result<Arc<regex::Regex>, &'static str>;

pub(crate) fn compile(pattern: &str) -> Compiled {
    regex::RegexBuilder::new(pattern)
        .size_limit(1_048_576)
        .dfa_size_limit(262_144)
        .nest_limit(32)
        .build()
        .map(Arc::new)
        .map_err(|_| "invalid or oversized regex")
}

#[derive(Default)]
pub(crate) struct RegexCache {
    entries: VecDeque<(TaskToken, String, Arc<regex::Regex>)>,
}

impl std::fmt::Debug for RegexCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RegexCache")
            .field("entries", &self.entries.len())
            .finish_non_exhaustive()
    }
}

impl RegexCache {
    pub(crate) fn get(&mut self, task: TaskToken, pattern: &str) -> Compiled {
        if pattern.is_empty() || pattern.len() > 128 {
            return Err("invalid or oversized regex");
        }
        if let Some(at) = self
            .entries
            .iter()
            .position(|(owner, text, _)| *owner == task && text == pattern)
        {
            let entry = self.entries.remove(at).expect("existing regex cache entry");
            let regex = Arc::clone(&entry.2);
            self.entries.push_front(entry);
            return Ok(regex);
        }
        let regex = compile(pattern)?;
        if self.entries.len() == 2 {
            self.entries.pop_back();
        }
        self.entries
            .push_front((task, pattern.to_owned(), Arc::clone(&regex)));
        Ok(regex)
    }

    pub(crate) fn clear_task(&mut self, task: TaskToken) {
        self.entries.retain(|(owner, _, _)| *owner != task);
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.entries.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use runtime_scheduler::{TaskGeneration, TaskId};

    fn owner(generation: u32) -> TaskToken {
        TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(generation),
        }
    }

    #[test]
    fn exact_task_pattern_lru_and_terminal_cleanup() {
        let mut cache = RegexCache::default();
        let a = cache.get(owner(1), "[0-9]+").unwrap();
        let b = cache.get(owner(1), "字+").unwrap();
        assert!(Arc::ptr_eq(&a, &cache.get(owner(1), "[0-9]+").unwrap()));
        let newer = cache.get(owner(2), "[0-9]+").unwrap();
        assert!(!Arc::ptr_eq(&a, &newer));
        assert_eq!(Arc::strong_count(&b), 1, "least recent pattern evicted");
        cache.clear_task(owner(1));
        assert_eq!(Arc::strong_count(&a), 1);
        assert_eq!(Arc::strong_count(&newer), 2);
        cache.clear_task(owner(2));
        assert!(cache.entries.is_empty());
        assert_eq!(Arc::strong_count(&newer), 1);
    }

    #[test]
    fn invalid_patterns_are_not_retained_and_debug_is_private() {
        let mut cache = RegexCache::default();
        cache.get(owner(1), "private-pattern").unwrap();
        assert!(cache.get(owner(1), "[").is_err());
        assert_eq!(cache.entries.len(), 1);
        assert!(!format!("{cache:?}").contains("private"));
        assert!(cache.get(owner(1), &"x".repeat(129)).is_err());
        assert!(cache.get(owner(1), "").is_err());
    }

    #[test]
    fn repeated_eviction_and_generation_cleanup_release_compiled_patterns() {
        let mut cache = RegexCache::default();
        for generation in 1..=2_000 {
            let task = owner(generation);
            let first = cache.get(task, "[0-9]+").unwrap();
            let retired = Arc::downgrade(&first);
            drop(first);
            cache.get(task, "[a-z]+").unwrap();
            let retained = cache.get(owner(generation + 1), "[A-Z]+").unwrap();
            assert_eq!(cache.len(), 2);
            assert!(retired.upgrade().is_none(), "LRU releases compiled code");
            cache.clear_task(task);
            assert_eq!(cache.len(), 1, "old task cannot clear new generation");
            let retired = Arc::downgrade(&retained);
            cache.clear_task(owner(generation + 1));
            assert_eq!(cache.len(), 0);
            assert!(retired.upgrade().is_some(), "active caller owns its code");
            drop(retained);
            assert!(retired.upgrade().is_none());
        }
    }

    #[test]
    fn cached_operations_preserve_unicode_matches_literal_replacement_and_errors() {
        use lua_runtime::LuaScalar;
        let mut cache = RegexCache::default();
        for source in [
            "",
            "智构😀A123 é45\nZ",
            "abababa",
            "a\0z",
            &"x ".repeat(65),
            &"a".repeat(4097),
        ] {
            for pattern in [
                "[0-9]+",
                "字|😀|é",
                "(?m)^",
                ".*?",
                "a|ab",
                "[",
                "(a)\\1",
                "",
                &"x".repeat(129),
            ] {
                let args = vec![
                    LuaScalar::Bytes(source.as_bytes().to_vec()),
                    LuaScalar::Bytes(pattern.as_bytes().to_vec()),
                ];
                for opcode in 14222..=14224 {
                    let reference = crate::text_runtime::execute(opcode, &args);
                    for _ in 0..2 {
                        assert_eq!(
                            reference,
                            crate::text_runtime::execute_with_regex(opcode, &args, |p| cache
                                .get(owner(1), p))
                        );
                    }
                }
                let mut replace = args;
                replace.push(LuaScalar::Bytes(b"$1\\literal".to_vec()));
                assert_eq!(
                    crate::pure_utility_runtime::execute(14226, &replace),
                    crate::pure_utility_runtime::execute_with_regex(14226, &replace, |p| cache
                        .get(owner(1), p))
                );
            }
        }
    }
}
