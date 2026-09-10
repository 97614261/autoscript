use runtime_scheduler::{RequestId, TaskToken};

use crate::{FrameFormat, FrameHandle, InputCommand};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackendError {
    pub code: &'static str,
    pub message: String,
    pub retryable: bool,
}

pub trait AutomationBackend {
    /// Dispatches one already-arbitrated atomic input transaction.
    ///
    /// # Errors
    ///
    /// Returns a stable backend error when the action cannot be accepted.
    fn dispatch_input(
        &mut self,
        request: RequestId,
        task: TaskToken,
        commands: &[InputCommand],
    ) -> Result<(), BackendError>;

    fn cancel_input(&mut self, request: RequestId);

    /// Releases every pointer still down for this engine session.
    ///
    /// # Errors
    ///
    /// Returns a backend error if cleanup could not be confirmed.
    fn release_pointers(&mut self, pointer_ids: &[u8]) -> Result<(), BackendError>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureRequest {
    pub preferred_format: FrameFormat,
    pub snapshot_id: u64,
}

pub trait CaptureBackend {
    /// Captures one frame into the engine-owned bounded frame pool.
    ///
    /// # Errors
    ///
    /// Returns a stable backend error for unavailable capture or invalid geometry.
    fn capture(&mut self, request: CaptureRequest) -> Result<FrameHandle, BackendError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OcrRequest {
    pub frame: FrameHandle,
    pub language: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OcrSpan {
    pub text: String,
    pub confidence: f32,
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OcrResult {
    pub spans: Vec<OcrSpan>,
    pub elapsed_micros: u64,
}

pub trait OcrEngine {
    /// Recognizes text from a leased immutable frame.
    ///
    /// # Errors
    ///
    /// Returns a stable error for unsupported languages, invalid handles or budget exhaustion.
    fn recognize(&mut self, request: &OcrRequest) -> Result<OcrResult, BackendError>;
}
