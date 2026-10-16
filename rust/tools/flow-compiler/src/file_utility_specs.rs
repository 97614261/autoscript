use crate::utility_operations::{Field, ScalarKind, Spec};
pub(crate) const SPECS: &[(&str, Spec)] = &[
    (
        "files.zip",
        Spec {
            api: "Files.zip",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "destination",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.unzip",
        Spec {
            api: "Files.unzip",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "destination",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.getsize",
        Spec {
            api: "Files.getSize",
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
        "files.getextension",
        Spec {
            api: "Files.getExtension",
            capability: "storage.data",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 1.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.directorystats",
        Spec {
            api: "Files.directoryStats",
            capability: "storage.data",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 1.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.tobase64",
        Spec {
            api: "Files.toBase64",
            capability: "storage.data",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 1.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.frombase64",
        Spec {
            api: "Files.fromBase64",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "base64",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.append",
        Spec {
            api: "Files.append",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.exists",
        Spec {
            api: "Files.exists",
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
        "files.isdir",
        Spec {
            api: "Files.isDir",
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
        "files.mkdir",
        Spec {
            api: "Files.mkdir",
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
        "files.copy",
        Spec {
            api: "Files.copy",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "destination",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.move",
        Spec {
            api: "Files.move",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "destination",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.rmdir",
        Spec {
            api: "Files.rmdir",
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
        "files.readline",
        Spec {
            api: "Files.readLine",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "line",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.insertline",
        Spec {
            api: "Files.insertLine",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "line",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.deleteline",
        Spec {
            api: "Files.deleteLine",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "line",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.linecount",
        Spec {
            api: "Files.lineCount",
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
        "files.writeline",
        Spec {
            api: "Files.writeLine",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "line",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.kvset",
        Spec {
            api: "Files.kvSet",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "key",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 128.0,
                },
                Field {
                    name: "valueJson",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.kvget",
        Spec {
            api: "Files.kvGet",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "key",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 128.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.kvremove",
        Spec {
            api: "Files.kvRemove",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "key",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 128.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "files.kvkeys",
        Spec {
            api: "Files.kvKeys",
            capability: "storage.data",
            fields: &[Field {
                name: "path",
                kind: ScalarKind::String,
                min: 1.0,
                max: 256.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "files.kvadd",
        Spec {
            api: "Files.kvAdd",
            capability: "storage.data",
            fields: &[
                Field {
                    name: "path",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 256.0,
                },
                Field {
                    name: "key",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 128.0,
                },
                Field {
                    name: "delta",
                    kind: ScalarKind::Integer,
                    min: -9_007_199_254_740_991.0,
                    max: 9_007_199_254_740_991.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
];
