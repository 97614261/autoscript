//! Strict text codecs and private-relative pure path tools.
use crate::utility_operations::{Field, ScalarKind, Spec};
pub(crate) const SPECS: &[(&str, Spec)] = &[
    (
        "text.urlencode",
        Spec {
            api: "Text.urlEncode",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65_536.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 16.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.urldecode",
        Spec {
            api: "Text.urlDecode",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65_536.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 16.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.tohex",
        Spec {
            api: "Text.toHex",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65_536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.fromhex",
        Spec {
            api: "Text.fromHex",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65_536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.tobase64",
        Spec {
            api: "Text.toBase64",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65_536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.frombase64",
        Spec {
            api: "Text.fromBase64",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65_536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.findnth",
        Spec {
            api: "Text.findNth",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65_536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 4_096.0,
                },
                Field {
                    name: "occurrence",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 1_024.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.betweennth",
        Spec {
            api: "Text.betweenNth",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65_536.0,
                },
                Field {
                    name: "left",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 4_096.0,
                },
                Field {
                    name: "right",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 4_096.0,
                },
                Field {
                    name: "occurrence",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 1_024.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "path.name",
        Spec {
            api: "Path.name",
            capability: "core.task",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 0.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "path.parent",
        Spec {
            api: "Path.parent",
            capability: "core.task",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 0.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "path.normalize",
        Spec {
            api: "Path.normalize",
            capability: "core.task",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 0.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "path.join",
        Spec {
            api: "Path.join",
            capability: "core.task",
            fields: &[
                Field {
                    name: "left",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 256.0,
                },
                Field {
                    name: "right",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 256.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
];
