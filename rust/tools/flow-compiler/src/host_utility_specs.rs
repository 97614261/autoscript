//! Fixed async host utility contracts. No arbitrary platform commands.
use crate::utility_operations::{Field, ScalarKind, Spec};
pub(crate) const SPECS: &[(&str, Spec)] = &[
    (
        "input.touchdown",
        Spec {
            api: "Input.touchDown",
            capability: "input.multi",
            fields: &[
                Field {
                    name: "pointerId",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 4.0,
                },
                Field {
                    name: "x",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 32767.0,
                },
                Field {
                    name: "y",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 32767.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "input.touchmove",
        Spec {
            api: "Input.touchMove",
            capability: "input.multi",
            fields: &[
                Field {
                    name: "pointerId",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 4.0,
                },
                Field {
                    name: "x",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 32767.0,
                },
                Field {
                    name: "y",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 32767.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "input.touchup",
        Spec {
            api: "Input.touchUp",
            capability: "input.multi",
            fields: &[Field {
                name: "pointerId",
                kind: ScalarKind::Integer,
                min: 0.0,
                max: 4.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "floating.image",
        Spec {
            api: "Floating.image",
            capability: "ui.floating.interactive",
            fields: &[
                Field {
                    name: "name",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 32.0,
                },
                Field {
                    name: "imagePath",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "x",
                    kind: ScalarKind::Integer,
                    min: -1.0,
                    max: 8192.0,
                },
                Field {
                    name: "y",
                    kind: ScalarKind::Integer,
                    min: -1.0,
                    max: 8192.0,
                },
                Field {
                    name: "width",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 2048.0,
                },
                Field {
                    name: "height",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 2048.0,
                },
                Field {
                    name: "draggable",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 1.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "floating.waitevent",
        Spec {
            api: "Floating.waitImageEvent",
            capability: "ui.floating.interactive",
            fields: &[
                Field {
                    name: "name",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 32.0,
                },
                Field {
                    name: "timeoutMs",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 60000.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "floating.closeimage",
        Spec {
            api: "Floating.closeImage",
            capability: "ui.floating.interactive",
            fields: &[Field {
                name: "name",
                kind: ScalarKind::String,
                min: 1.0,
                max: 32.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.modifiedtime",
        Spec {
            api: "Files.modifiedTime",
            capability: "storage.data",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 1.0,
                max: 256.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "system.info",
        Spec {
            api: "System.info",
            capability: "device.info",
            fields: &[Field {
                name: "section",
                kind: ScalarKind::String,
                min: 1.0,
                max: 16.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "system.keepawake",
        Spec {
            api: "System.keepScreenAwake",
            capability: "device.display",
            fields: &[Field {
                name: "enabled",
                kind: ScalarKind::Integer,
                min: 0.0,
                max: 1.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "app.openurl",
        Spec {
            api: "Apps.openUrl",
            capability: "device.apps",
            fields: &[Field {
                name: "url",
                kind: ScalarKind::String,
                min: 1.0,
                max: 2_048.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "network.request",
        Spec {
            api: "Network.request",
            capability: "network.http",
            fields: &[
                Field {
                    name: "method",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 8.0,
                },
                Field {
                    name: "url",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 2_048.0,
                },
                Field {
                    name: "headersJson",
                    kind: ScalarKind::String,
                    min: 2.0,
                    max: 4_096.0,
                },
                Field {
                    name: "body",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 8_192.0,
                },
                Field {
                    name: "timeoutMs",
                    kind: ScalarKind::Integer,
                    min: 100.0,
                    max: 30_000.0,
                },
                Field {
                    name: "maxResponseBytes",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 16_384.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "input.imestatus",
        Spec {
            api: "Input.imeStatus",
            capability: "input.ime",
            fields: &[],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "input.imetext",
        Spec {
            api: "Input.imeText",
            capability: "input.ime",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 4096.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
];
