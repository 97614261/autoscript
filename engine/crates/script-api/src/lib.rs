#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutionMode {
    RustSync,
    HostSync,
    AsyncTask,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CancelMode {
    None,
    DiscardResult,
    Cooperative,
    AbortSafe,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimeApiVersion {
    pub major: u16,
    pub minor: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ApiParameter {
    pub name: &'static str,
    pub type_name: &'static str,
    pub required: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ApiContract {
    pub opcode: u32,
    pub name: &'static str,
    pub since: &'static str,
    pub capability: &'static str,
    pub execution: ExecutionMode,
    pub cancel_mode: CancelMode,
    pub side_effect: &'static str,
    pub timeout_ms: Option<u64>,
    pub resource_ownership: &'static str,
    pub return_type: &'static str,
    pub params: &'static [ApiParameter],
}

include!(concat!(env!("OUT_DIR"), "/api_registry.rs"));

impl RuntimeApiVersion {
    #[must_use]
    pub const fn is_compatible_with(self, required: Self) -> bool {
        self.major == required.major && self.minor >= required.minor
    }
}

#[cfg(test)]
mod tests {
    use super::{RuntimeApiVersion, API_CONTRACTS};

    #[test]
    fn compatibility_requires_same_major_and_sufficient_minor() {
        let host = RuntimeApiVersion { major: 1, minor: 5 };
        assert!(host.is_compatible_with(RuntimeApiVersion { major: 1, minor: 2 }));
        assert!(!host.is_compatible_with(RuntimeApiVersion { major: 2, minor: 0 }));
    }

    #[test]
    fn generated_contracts_are_sorted_and_unique() {
        assert_eq!(API_CONTRACTS.len(), 26);
        assert!(API_CONTRACTS
            .windows(2)
            .all(|pair| pair[0].opcode < pair[1].opcode));
    }
}
