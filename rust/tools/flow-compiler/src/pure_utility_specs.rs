//! Fixed pure utility contracts; no executable expressions or implicit conversion.
use crate::utility_operations::{Field, ScalarKind, Spec};
pub(crate) const SPECS: &[(&str, Spec)] = &[
    (
        "text.numberat",
        Spec {
            api: "Text.numberAt",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
                Field {
                    name: "index",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 64.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "math.unary",
        Spec {
            api: "Math.unary",
            capability: "core.math",
            fields: &[
                Field {
                    name: "operation",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 16.0,
                },
                Field {
                    name: "value",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "math.binary",
        Spec {
            api: "Math.binary",
            capability: "core.math",
            fields: &[
                Field {
                    name: "operation",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 16.0,
                },
                Field {
                    name: "left",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "right",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "math.roundto",
        Spec {
            api: "Math.roundTo",
            capability: "core.math",
            fields: &[
                Field {
                    name: "value",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "digits",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 9.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "text.formatnumber",
        Spec {
            api: "Text.formatNumber",
            capability: "core.task",
            fields: &[
                Field {
                    name: "value",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "digits",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 9.0,
                },
                Field {
                    name: "minIntegerDigits",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 32.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.regexreplace",
        Spec {
            api: "Text.regexReplace",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
                Field {
                    name: "pattern",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 128.0,
                },
                Field {
                    name: "replacement",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.filter",
        Spec {
            api: "Text.filter",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 24.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.reverse",
        Spec {
            api: "Text.reverse",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "data.encode",
        Spec {
            api: "Data.encode",
            capability: "core.task",
            fields: &[
                Field {
                    name: "value",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 8.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "data.sort",
        Spec {
            api: "Data.sort",
            capability: "core.task",
            fields: &[
                Field {
                    name: "json",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 16.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
];
pub(crate) fn choices(kind: &str, field: &str) -> Option<&'static [&'static str]> {
    match (kind, field) {
        ("system.info", "section") => Some(&[
            "device",
            "display",
            "battery",
            "network",
            "storage",
            "permissions",
        ]),
        ("network.request", "method") => Some(&["GET", "POST", "PUT", "OPTIONS", "DELETE", "HEAD"]),
        ("text.urlencode" | "text.urldecode", "mode") => Some(&["component", "form"]),
        ("math.unary", "operation") => Some(&[
            "abs", "ceil", "floor", "sqrt", "ln", "log10", "exp", "sin", "cos", "tan", "asin",
            "acos", "atan", "deg", "rad",
        ]),
        ("math.binary", "operation") => Some(&["min", "max", "pow", "atan2", "log"]),
        ("text.filter", "mode") => Some(&[
            "keepDigits",
            "removeDigits",
            "keepLetters",
            "removeLetters",
            "keepAlnum",
            "removeAlnum",
            "keepHan",
            "removeHan",
            "keepWhitespace",
            "removeWhitespace",
        ]),
        ("data.encode", "mode") => Some(&["text", "json"]),
        ("data.sort", "mode") => Some(&["numberAsc", "numberDesc", "textAsc", "textDesc"]),
        _ => None,
    }
}
