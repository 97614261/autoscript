//! Explicit, bounded extra foreground colors. No filtering or dictionary mutation.
pub const MAX_GLYPH_COLORS: usize = 16;
pub const MAX_COLOR_COMPARISONS: u64 = 64_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlyphColorRule {
    pub rgb: u32,
    pub tolerance: u8,
}

/// Empty preserves single-color calls. Otherwise accept up to 15 RRGGBB:0..255 entries.
/// # Errors
/// Rejects non-canonical structure, duplicate RGBs and excessive input before allocation.
pub fn parse_additional_colors(value: &str) -> Result<Vec<GlyphColorRule>, &'static str> {
    if value.is_empty() {
        return Ok(Vec::new());
    }
    if value.len() > 164 || value.split(';').count() >= MAX_GLYPH_COLORS {
        return Err("glyph colors exceed budget");
    }
    let mut output = Vec::new();
    for entry in value.split(';') {
        let (rgb, tolerance) = entry.split_once(':').ok_or("invalid glyph color")?;
        if rgb.len() != 6
            || !rgb.bytes().all(|c| c.is_ascii_hexdigit())
            || tolerance.is_empty()
            || tolerance.len() > 3
            || !tolerance.bytes().all(|c| c.is_ascii_digit())
        {
            return Err("invalid glyph color");
        }
        let rgb = u32::from_str_radix(rgb, 16).map_err(|_| "invalid glyph RGB")?;
        let tolerance = tolerance
            .parse::<u8>()
            .map_err(|_| "invalid glyph tolerance")?;
        if output.iter().any(|rule: &GlyphColorRule| rule.rgb == rgb) {
            return Err("duplicate glyph color");
        }
        output.push(GlyphColorRule { rgb, tolerance });
    }
    Ok(output)
}

/// # Errors
/// A palette cannot repeat the primary color or exceed the fixed color budget.
pub fn validate_additional_colors(
    primary: u32,
    colors: &[GlyphColorRule],
) -> Result<(), &'static str> {
    if primary > 0xff_ffff || colors.len() >= MAX_GLYPH_COLORS {
        return Err("invalid glyph palette");
    }
    for (index, color) in colors.iter().enumerate() {
        if color.rgb > 0xff_ffff
            || color.rgb == primary
            || colors[..index].iter().any(|old| old.rgb == color.rgb)
        {
            return Err("invalid or duplicate glyph color");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_palette_rejects_partial_duplicate_and_excessive_rules() {
        assert!(parse_additional_colors("").unwrap().is_empty());
        assert_eq!(
            parse_additional_colors("FF0000:8;00ff00:255").unwrap(),
            vec![
                GlyphColorRule {
                    rgb: 0x00ff_0000,
                    tolerance: 8
                },
                GlyphColorRule {
                    rgb: 0x0000_ff00,
                    tolerance: 255
                }
            ]
        );
        for bad in [
            "#FF0000:8",
            "FF0000:-1",
            "FF0000:256",
            "FF0000:",
            "FF0000:0;",
            "FF0000:0;ff0000:1",
            " FFFFFF:0",
        ] {
            assert!(parse_additional_colors(bad).is_err(), "{bad}");
        }
        let colors = parse_additional_colors("FF0000:8").unwrap();
        assert!(validate_additional_colors(0x00ff_0000, &colors).is_err());
        assert!(validate_additional_colors(0x00ff_ffff, &colors).is_ok());
    }
}
