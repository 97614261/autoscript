//! Deterministic, bounded bitmap-font OCR for fixed game and application text.

mod format;
mod recognize;
mod store;

pub use format::{
    decode_dictionary, encode_dictionary, DictionaryFormatError, GlyphBitmap, GlyphDictionary,
    GlyphSource,
};
pub use recognize::{
    recognize, GlyphMatch, GlyphOcrError, GlyphOcrOptions, GlyphOcrResult, GlyphRect,
};
pub use store::{DictionaryHandle, DictionaryStore, DictionaryStoreConfig, DictionaryStoreError};
