//! User-defined keys. Generated identities and protocol identifiers stay ASCII.
#[must_use]
pub fn user_name_start(ch: char) -> bool {
    ch.is_ascii_alphabetic()
        || ch == '_'
        || matches!(ch, '\u{3400}'..='\u{4dbf}' | '\u{4e00}'..='\u{9fff}')
}

#[must_use]
pub fn user_name_part(ch: char) -> bool {
    user_name_start(ch) || ch.is_ascii_digit()
}

#[must_use]
pub fn valid_user_name(value: &str) -> bool {
    let mut chars = value.chars();
    chars.next().is_some_and(user_name_start)
        && value.chars().count() <= 64
        && chars.all(user_name_part)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_bounded_chinese_keys_not_executable_identifiers() {
        for value in ["剩余次数", "目标图片_1", "A_变量9", "㐀", &"字".repeat(64)] {
            assert!(valid_user_name(value), "{value}");
        }
        for value in [
            "",
            "1次数",
            "变量 名",
            "变量.x",
            "值[1]",
            "a\u{200b}",
            "😀",
            &"字".repeat(65),
        ] {
            assert!(!valid_user_name(value), "{value}");
        }
    }
}
