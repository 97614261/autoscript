use std::collections::BTreeMap;
use std::sync::Arc;

use runtime_scheduler::{ResourceId, ResourceKind, ResourceOwner, TaskResourceRegistry, TaskToken};

use crate::{decode_dictionary, DictionaryFormatError, GlyphDictionary};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DictionaryStoreConfig {
    pub max_dictionaries: usize,
    pub max_total_bytes: usize,
}

impl Default for DictionaryStoreConfig {
    fn default() -> Self {
        Self {
            max_dictionaries: 16,
            max_total_bytes: 16 * 1024 * 1024,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DictionaryHandle {
    pub dictionary_id: u64,
    pub resource_id: ResourceId,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DictionaryStoreError {
    InvalidPath,
    DuplicatePath(String),
    BudgetExceeded,
    DictionaryIdExhausted,
    Format(DictionaryFormatError),
    UnknownDictionary(DictionaryHandle),
    LeaseNotHeld(DictionaryHandle),
    Resource(String),
}

#[derive(Debug)]
struct RegisteredDictionary {
    dictionary: Arc<GlyphDictionary>,
    byte_length: usize,
}

#[derive(Debug)]
pub struct DictionaryStore {
    config: DictionaryStoreConfig,
    next_dictionary_id: u64,
    total_bytes: usize,
    registered: BTreeMap<String, RegisteredDictionary>,
    leased: BTreeMap<u64, Arc<GlyphDictionary>>,
}

impl DictionaryStore {
    #[must_use]
    pub fn new(config: DictionaryStoreConfig) -> Self {
        Self {
            config,
            next_dictionary_id: 1,
            total_bytes: 0,
            registered: BTreeMap::new(),
            leased: BTreeMap::new(),
        }
    }

    #[must_use]
    pub fn registered_count(&self) -> usize {
        self.registered.len()
    }

    /// Registers one strictly parsed project dictionary under a canonical resource path.
    ///
    /// # Errors
    ///
    /// Rejects unsafe/duplicate paths, malformed dictionaries, or the project byte budget.
    pub fn register(&mut self, path: &str, bytes: &[u8]) -> Result<(), DictionaryStoreError> {
        if !valid_dictionary_path(path) {
            return Err(DictionaryStoreError::InvalidPath);
        }
        if self.registered.contains_key(path) {
            return Err(DictionaryStoreError::DuplicatePath(path.to_owned()));
        }
        if self.registered.len() >= self.config.max_dictionaries
            || self.total_bytes.saturating_add(bytes.len()) > self.config.max_total_bytes
        {
            return Err(DictionaryStoreError::BudgetExceeded);
        }
        let dictionary = decode_dictionary(bytes).map_err(DictionaryStoreError::Format)?;
        self.total_bytes += bytes.len();
        self.registered.insert(
            path.to_owned(),
            RegisteredDictionary {
                dictionary: Arc::new(dictionary),
                byte_length: bytes.len(),
            },
        );
        Ok(())
    }

    /// Gives one task a typed lease over a registered dictionary.
    ///
    /// # Errors
    ///
    /// Rejects unknown paths, exhausted identifiers, or scheduler resource conflicts.
    pub fn lease(
        &mut self,
        task: TaskToken,
        path: &str,
        resources: &mut TaskResourceRegistry,
    ) -> Result<DictionaryHandle, DictionaryStoreError> {
        let dictionary = Arc::clone(
            &self
                .registered
                .get(path)
                .ok_or(DictionaryStoreError::InvalidPath)?
                .dictionary,
        );
        let id = self.next_dictionary_id;
        self.next_dictionary_id = id
            .checked_add(1)
            .ok_or(DictionaryStoreError::DictionaryIdExhausted)?;
        let resource_id = ResourceId::try_new(ResourceKind::GlyphDictionary, id)
            .ok_or(DictionaryStoreError::DictionaryIdExhausted)?;
        let handle = DictionaryHandle {
            dictionary_id: id,
            resource_id,
        };
        self.leased.insert(id, dictionary);
        if let Err(error) = resources.acquire_new(ResourceOwner::Task(task), resource_id) {
            self.leased.remove(&id);
            return Err(DictionaryStoreError::Resource(format!("{error:?}")));
        }
        Ok(handle)
    }

    /// Resolves one dictionary only after proving current task ownership.
    ///
    /// # Errors
    ///
    /// Rejects a stale handle or a handle owned by a different task.
    pub fn get(
        &self,
        task: TaskToken,
        handle: DictionaryHandle,
        resources: &TaskResourceRegistry,
    ) -> Result<Arc<GlyphDictionary>, DictionaryStoreError> {
        if !resources.holds(ResourceOwner::Task(task), handle.resource_id) {
            return Err(DictionaryStoreError::LeaseNotHeld(handle));
        }
        self.leased
            .get(&handle.dictionary_id)
            .cloned()
            .ok_or(DictionaryStoreError::UnknownDictionary(handle))
    }

    /// Drops live handle state after the scheduler reports the final lease vanished.
    pub fn release_resource(&mut self, resource: ResourceId) -> bool {
        resource.kind() == ResourceKind::GlyphDictionary
            && self.leased.remove(&resource.local_id()).is_some()
    }

    #[must_use]
    pub fn registered_bytes(&self) -> usize {
        self.registered
            .values()
            .map(|entry| entry.byte_length)
            .sum()
    }
}

fn valid_dictionary_path(path: &str) -> bool {
    path.len() <= 256
        && path.starts_with("dictionaries/")
        && !path.contains(['\\', '\0'])
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

#[cfg(test)]
mod tests {
    use runtime_scheduler::{TaskGeneration, TaskId};

    use crate::{encode_dictionary, GlyphSource};

    use super::*;

    fn task(id: u64) -> TaskToken {
        TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        }
    }

    fn fixture() -> Vec<u8> {
        encode_dictionary(&[GlyphSource {
            label: "X".to_owned(),
            width: 1,
            height: 1,
            packed_bits: vec![0x80],
        }])
        .expect("dictionary")
    }

    #[test]
    fn dictionary_handles_are_typed_task_leases() {
        let mut store = DictionaryStore::new(DictionaryStoreConfig::default());
        store
            .register("dictionaries/main.asglyph", &fixture())
            .expect("register");
        assert_eq!(store.registered_bytes(), fixture().len());
        let mut resources = TaskResourceRegistry::default();
        let handle = store
            .lease(task(1), "dictionaries/main.asglyph", &mut resources)
            .expect("lease");
        assert_eq!(handle.resource_id.kind(), ResourceKind::GlyphDictionary);
        assert!(store.get(task(1), handle, &resources).is_ok());
        assert_eq!(
            store.get(task(2), handle, &resources),
            Err(DictionaryStoreError::LeaseNotHeld(handle))
        );
        assert!(resources
            .release_lease(ResourceOwner::Task(task(1)), handle.resource_id)
            .expect("release"));
        assert!(store.release_resource(handle.resource_id));
    }
}
