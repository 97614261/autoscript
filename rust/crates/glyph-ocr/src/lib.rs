//! Deterministic, bounded bitmap-font OCR for fixed game and application text.

mod format;
mod palette;
mod recognize;
mod store;

pub use format::{
    decode_dictionary, encode_dictionary, DictionaryFormatError, GlyphBitmap, GlyphDictionary,
    GlyphSource,
};
pub use palette::{
    parse_additional_colors, validate_additional_colors, GlyphColorRule, MAX_COLOR_COMPARISONS,
    MAX_GLYPH_COLORS,
};
pub use recognize::{
    recognize, recognize_cancellable, recognize_palette_cancellable, GlyphMatch, GlyphOcrError,
    GlyphOcrOptions, GlyphOcrResult, GlyphRect,
};
pub use store::{DictionaryHandle, DictionaryStore, DictionaryStoreConfig, DictionaryStoreError};
