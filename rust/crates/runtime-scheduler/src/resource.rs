use std::collections::BTreeMap;

use crate::{ResourceId, TaskToken};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ResourceOwner {
    Task(TaskToken),
    Outcome(TaskToken),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResourceError {
    AlreadyRegistered(ResourceId),
    UnknownResource(ResourceId),
    LeaseNotHeld {
        resource: ResourceId,
        owner: ResourceOwner,
    },
    LeaseCountOverflow(ResourceId),
}

#[derive(Debug, Default)]
struct ResourceRecord {
    leases: BTreeMap<ResourceOwner, u32>,
}

#[derive(Debug, Default)]
pub struct TaskResourceRegistry {
    resources: BTreeMap<ResourceId, ResourceRecord>,
}

impl TaskResourceRegistry {
    #[must_use]
    pub fn resource_count(&self) -> usize {
        self.resources.len()
    }

    /// Registers a newly-created host resource and gives its first lease to `owner`.
    ///
    /// # Errors
    ///
    /// Returns an error if the resource identifier is already live.
    pub fn acquire_new(
        &mut self,
        owner: ResourceOwner,
        resource: ResourceId,
    ) -> Result<(), ResourceError> {
        if self.resources.contains_key(&resource) {
            return Err(ResourceError::AlreadyRegistered(resource));
        }
        let mut record = ResourceRecord::default();
        record.leases.insert(owner, 1);
        self.resources.insert(resource, record);
        Ok(())
    }

    /// Adds one lease for `owner`, after proving that `source` already owns one.
    ///
    /// # Errors
    ///
    /// Returns an error for unknown resources, missing source leases or count overflow.
    pub fn copy_lease(
        &mut self,
        source: ResourceOwner,
        owner: ResourceOwner,
        resource: ResourceId,
    ) -> Result<(), ResourceError> {
        let record = self
            .resources
            .get_mut(&resource)
            .ok_or(ResourceError::UnknownResource(resource))?;
        if !record.leases.contains_key(&source) {
            return Err(ResourceError::LeaseNotHeld {
                resource,
                owner: source,
            });
        }
        let count = record.leases.get(&owner).copied().unwrap_or_default();
        let next = count
            .checked_add(1)
            .ok_or(ResourceError::LeaseCountOverflow(resource))?;
        record.leases.insert(owner, next);
        Ok(())
    }

    /// Moves every lease for one resource from `source` to `owner`.
    ///
    /// # Errors
    ///
    /// Returns an error for unknown resources, missing source leases or count overflow.
    pub fn transfer_resource(
        &mut self,
        source: ResourceOwner,
        owner: ResourceOwner,
        resource: ResourceId,
    ) -> Result<(), ResourceError> {
        let record = self
            .resources
            .get_mut(&resource)
            .ok_or(ResourceError::UnknownResource(resource))?;
        let moved = *record
            .leases
            .get(&source)
            .ok_or(ResourceError::LeaseNotHeld {
                resource,
                owner: source,
            })?;
        if source == owner {
            return Ok(());
        }
        let destination = record.leases.get(&owner).copied().unwrap_or_default();
        let merged = destination
            .checked_add(moved)
            .ok_or(ResourceError::LeaseCountOverflow(resource))?;
        record.leases.remove(&source);
        record.leases.insert(owner, merged);
        Ok(())
    }

    #[must_use]
    pub fn holds(&self, owner: ResourceOwner, resource: ResourceId) -> bool {
        self.resources
            .get(&resource)
            .is_some_and(|record| record.leases.contains_key(&owner))
    }

    /// Releases all leases held by `owner` and returns resources whose final lease vanished.
    pub fn release_owner(&mut self, owner: ResourceOwner) -> Vec<ResourceId> {
        let mut released = Vec::new();
        self.resources.retain(|resource, record| {
            record.leases.remove(&owner);
            if record.leases.is_empty() {
                released.push(*resource);
                false
            } else {
                true
            }
        });
        released
    }

    /// Releases one lease and reports whether the final lease vanished.
    ///
    /// # Errors
    ///
    /// Returns an error for an unknown resource or an owner that holds no lease.
    pub fn release_lease(
        &mut self,
        owner: ResourceOwner,
        resource: ResourceId,
    ) -> Result<bool, ResourceError> {
        let record = self
            .resources
            .get_mut(&resource)
            .ok_or(ResourceError::UnknownResource(resource))?;
        let count = record
            .leases
            .get_mut(&owner)
            .ok_or(ResourceError::LeaseNotHeld { resource, owner })?;
        if *count > 1 {
            *count -= 1;
            return Ok(false);
        }
        record.leases.remove(&owner);
        if record.leases.is_empty() {
            self.resources.remove(&resource);
            Ok(true)
        } else {
            Ok(false)
        }
    }

    /// Transfers all leases held by one owner to another owner.
    ///
    /// # Errors
    ///
    /// Returns an error if merging lease counts would overflow.
    pub fn transfer_owner(
        &mut self,
        source: ResourceOwner,
        owner: ResourceOwner,
    ) -> Result<Vec<ResourceId>, ResourceError> {
        let ids = self
            .resources
            .iter()
            .filter_map(|(id, record)| record.leases.contains_key(&source).then_some(*id))
            .collect::<Vec<_>>();
        if source == owner {
            return Ok(ids);
        }
        for id in &ids {
            let record = self
                .resources
                .get(id)
                .ok_or(ResourceError::UnknownResource(*id))?;
            let source_count =
                record
                    .leases
                    .get(&source)
                    .copied()
                    .ok_or(ResourceError::LeaseNotHeld {
                        resource: *id,
                        owner: source,
                    })?;
            let owner_count = record.leases.get(&owner).copied().unwrap_or_default();
            owner_count
                .checked_add(source_count)
                .ok_or(ResourceError::LeaseCountOverflow(*id))?;
        }
        for id in &ids {
            self.transfer_resource(source, owner, *id)?;
        }
        Ok(ids)
    }
}

#[cfg(test)]
mod tests {
    use crate::{ResourceId, ResourceKind, TaskGeneration, TaskId, TaskToken};

    use super::{ResourceOwner, TaskResourceRegistry};

    fn task(id: u64) -> ResourceOwner {
        ResourceOwner::Task(TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        })
    }

    fn resource(local_id: u64) -> ResourceId {
        ResourceId::try_new(ResourceKind::Generic, local_id).expect("test resource id")
    }

    #[test]
    fn final_lease_reports_resource_for_host_release() {
        let mut registry = TaskResourceRegistry::default();
        registry
            .acquire_new(task(1), resource(7))
            .expect("new resource");
        registry
            .copy_lease(task(1), task(2), resource(7))
            .expect("copy child lease");

        assert!(registry.release_owner(task(1)).is_empty());
        assert_eq!(registry.release_owner(task(2)), [resource(7)]);
    }

    #[test]
    fn outcome_can_transfer_custody_to_joiner() {
        let mut registry = TaskResourceRegistry::default();
        let outcome = ResourceOwner::Outcome(TaskToken {
            id: TaskId(2),
            generation: TaskGeneration(1),
        });
        registry
            .acquire_new(task(2), resource(9))
            .expect("new resource");
        registry
            .transfer_resource(task(2), outcome, resource(9))
            .expect("return resource");
        registry
            .transfer_owner(outcome, task(1))
            .expect("join transfers result custody");

        assert!(registry.holds(task(1), resource(9)));
        assert!(!registry.holds(outcome, resource(9)));
    }

    #[test]
    fn one_lease_can_be_released_without_touching_other_owners() {
        let mut registry = TaskResourceRegistry::default();
        registry
            .acquire_new(task(1), resource(11))
            .expect("new resource");
        registry
            .copy_lease(task(1), task(2), resource(11))
            .expect("second owner");
        assert!(!registry
            .release_lease(task(1), resource(11))
            .expect("first release"));
        assert!(registry.holds(task(2), resource(11)));
        assert!(registry
            .release_lease(task(2), resource(11))
            .expect("final release"));
        assert_eq!(registry.resource_count(), 0);
    }
}
