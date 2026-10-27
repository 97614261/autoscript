//! Typed utility blocks reuse fixed API calls, never concatenate executable expressions.
use crate::model::CompileError;
use flow_ir::ProjectVariableType;
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ScalarKind {
    Integer,
    Number,
    String,
}
impl ScalarKind {
    fn accepts(self, actual: Option<ProjectVariableType>) -> bool {
        match self {
            Self::Integer => actual == Some(ProjectVariableType::Integer),
            Self::Number => matches!(
                actual,
                Some(ProjectVariableType::Integer | ProjectVariableType::Number)
            ),
            Self::String => actual == Some(ProjectVariableType::String),
        }
    }
}
#[derive(Debug, Clone, Copy)]
pub(crate) struct Field {
    pub name: &'static str,
    pub kind: ScalarKind,
    pub min: f64,
    pub max: f64,
}
#[derive(Clone, Copy)]
pub(crate) struct Spec {
    pub api: &'static str,
    pub capability: &'static str,
    pub fields: &'static [Field],
    pub result: ScalarKind,
    pub size: bool,
}
pub(crate) const SPECS: &[(&str, Spec)] = &[
    (
        "math.randint",
        Spec {
            api: "Math.randInt",
            capability: "core.math",
            fields: &[
                Field {
                    name: "min",
                    kind: ScalarKind::Integer,
                    min: -9_007_199_254_740_991.0,
                    max: 9_007_199_254_740_991.0,
                },
                Field {
                    name: "max",
                    kind: ScalarKind::Integer,
                    min: -9_007_199_254_740_991.0,
                    max: 9_007_199_254_740_991.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.randstr",
        Spec {
            api: "Math.randStr",
            capability: "core.math",
            fields: &[
                Field {
                    name: "length",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 1_024.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 5.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "math.round",
        Spec {
            api: "Math.round",
            capability: "core.math",
            fields: &[Field {
                name: "value",
                kind: ScalarKind::Number,
                min: -1_000_000_000_000.0,
                max: 1_000_000_000_000.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.clamp",
        Spec {
            api: "Math.clamp",
            capability: "core.math",
            fields: &[
                Field {
                    name: "value",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "min",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "max",
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
        "math.inrange",
        Spec {
            api: "Math.inRange",
            capability: "core.math",
            fields: &[
                Field {
                    name: "value",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "min",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "max",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.angle",
        Spec {
            api: "Math.angle",
            capability: "core.math",
            fields: &[
                Field {
                    name: "x1",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "y1",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "x2",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "y2",
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
        "math.flipangle",
        Spec {
            api: "Math.flipAngle",
            capability: "core.math",
            fields: &[
                Field {
                    name: "angle",
                    kind: ScalarKind::Number,
                    min: -180.0,
                    max: 180.0,
                },
                Field {
                    name: "mode",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 2.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "math.joystickx",
        Spec {
            api: "Math.joystickX",
            capability: "core.math",
            fields: &[
                Field {
                    name: "centerX",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "angle",
                    kind: ScalarKind::Number,
                    min: -180.0,
                    max: 180.0,
                },
                Field {
                    name: "radius",
                    kind: ScalarKind::Number,
                    min: 0.0,
                    max: 1_000_000_000.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "math.joysticky",
        Spec {
            api: "Math.joystickY",
            capability: "core.math",
            fields: &[
                Field {
                    name: "centerY",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "angle",
                    kind: ScalarKind::Number,
                    min: -180.0,
                    max: 180.0,
                },
                Field {
                    name: "radius",
                    kind: ScalarKind::Number,
                    min: 0.0,
                    max: 1_000_000_000.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
    (
        "math.red",
        Spec {
            api: "Math.red",
            capability: "core.math",
            fields: &[Field {
                name: "rgb",
                kind: ScalarKind::Integer,
                min: 0.0,
                max: 16_777_215.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.green",
        Spec {
            api: "Math.green",
            capability: "core.math",
            fields: &[Field {
                name: "rgb",
                kind: ScalarKind::Integer,
                min: 0.0,
                max: 16_777_215.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.blue",
        Spec {
            api: "Math.blue",
            capability: "core.math",
            fields: &[Field {
                name: "rgb",
                kind: ScalarKind::Integer,
                min: 0.0,
                max: 16_777_215.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "math.distance",
        Spec {
            api: "Math.distance",
            capability: "core.math",
            fields: &[
                Field {
                    name: "x1",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "y1",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "x2",
                    kind: ScalarKind::Number,
                    min: -1_000_000_000_000.0,
                    max: 1_000_000_000_000.0,
                },
                Field {
                    name: "y2",
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
        "system.elapsed",
        Spec {
            api: "System.elapsedRealtimeMillis",
            capability: "core.task",
            fields: &[],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "system.time",
        Spec {
            api: "System.getTimeMillis",
            capability: "core.task",
            fields: &[],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "system.screensize",
        Spec {
            api: "System.getScreenSize",
            capability: "device.display",
            fields: &[],
            result: ScalarKind::Integer,
            size: true,
        },
    ),
    (
        "text.length",
        Spec {
            api: "Text.length",
            capability: "core.task",
            fields: &[Field {
                name: "text",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65536.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.contains",
        Spec {
            api: "Text.contains",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.startswith",
        Spec {
            api: "Text.startsWith",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.endswith",
        Spec {
            api: "Text.endsWith",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.count",
        Spec {
            api: "Text.count",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.split",
        Spec {
            api: "Text.split",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "separator",
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
        "text.join",
        Spec {
            api: "Text.join",
            capability: "core.task",
            fields: &[
                Field {
                    name: "json",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "separator",
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
        "text.left",
        Spec {
            api: "Text.left",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "length",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.right",
        Spec {
            api: "Text.right",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "length",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.sub",
        Spec {
            api: "Text.sub",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "start",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
                Field {
                    name: "length",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.insert",
        Spec {
            api: "Text.insert",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "start",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
                Field {
                    name: "value",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.remove",
        Spec {
            api: "Text.remove",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "start",
                    kind: ScalarKind::Integer,
                    min: 1.0,
                    max: 65537.0,
                },
                Field {
                    name: "length",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.replace",
        Spec {
            api: "Text.replace",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
                Field {
                    name: "replacement",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.replacefirst",
        Spec {
            api: "Text.replaceFirst",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
                Field {
                    name: "replacement",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.repeat",
        Spec {
            api: "Text.repeatText",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "count",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 1024.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.between",
        Spec {
            api: "Text.between",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "left",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 4096.0,
                },
                Field {
                    name: "right",
                    kind: ScalarKind::String,
                    min: 1.0,
                    max: 4096.0,
                },
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.before",
        Spec {
            api: "Text.before",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
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
        "text.after",
        Spec {
            api: "Text.after",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
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
        "text.trim",
        Spec {
            api: "Text.trim",
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
        "text.find",
        Spec {
            api: "Text.find",
            capability: "core.task",
            fields: &[
                Field {
                    name: "text",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
                },
                Field {
                    name: "needle",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 4096.0,
                },
                Field {
                    name: "start",
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
        "text.lower",
        Spec {
            api: "Text.lower",
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
        "text.upper",
        Spec {
            api: "Text.upper",
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
        "text.regexmatch",
        Spec {
            api: "Text.regexMatch",
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
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "text.regexextract",
        Spec {
            api: "Text.regexExtract",
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
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "text.regexall",
        Spec {
            api: "Text.regexAll",
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
            ],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "data.valid",
        Spec {
            api: "Data.valid",
            capability: "core.task",
            fields: &[Field {
                name: "json",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65536.0,
            }],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "data.type",
        Spec {
            api: "Data.type",
            capability: "core.task",
            fields: &[Field {
                name: "json",
                kind: ScalarKind::String,
                min: 0.0,
                max: 65536.0,
            }],
            result: ScalarKind::String,
            size: false,
        },
    ),
    (
        "data.has",
        Spec {
            api: "Data.has",
            capability: "core.task",
            fields: &[
                Field {
                    name: "json",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
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
        "data.getjson",
        Spec {
            api: "Data.getJson",
            capability: "core.task",
            fields: &[
                Field {
                    name: "json",
                    kind: ScalarKind::String,
                    min: 0.0,
                    max: 65536.0,
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
        "date.format",
        Spec {
            api: "Date.format",
            capability: "core.task",
            fields: &[
                Field {
                    name: "timestampMs",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 253_402_300_799_999.0,
                },
                Field {
                    name: "offsetMinutes",
                    kind: ScalarKind::Integer,
                    min: -840.0,
                    max: 840.0,
                },
                Field {
                    name: "format",
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
        "date.adddays",
        Spec {
            api: "Date.addDays",
            capability: "core.task",
            fields: &[
                Field {
                    name: "timestampMs",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 253_402_300_799_999.0,
                },
                Field {
                    name: "days",
                    kind: ScalarKind::Integer,
                    min: -3_652_058.0,
                    max: 3_652_058.0,
                },
            ],
            result: ScalarKind::Integer,
            size: false,
        },
    ),
    (
        "date.daysbetween",
        Spec {
            api: "Date.daysBetween",
            capability: "core.task",
            fields: &[
                Field {
                    name: "startMs",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 253_402_300_799_999.0,
                },
                Field {
                    name: "endMs",
                    kind: ScalarKind::Integer,
                    min: 0.0,
                    max: 253_402_300_799_999.0,
                },
            ],
            result: ScalarKind::Number,
            size: false,
        },
    ),
];
pub(crate) fn spec(kind: &str) -> Option<Spec> {
    SPECS
        .iter()
        .chain(crate::file_utility_specs::SPECS.iter())
        .chain(crate::pure_utility_specs::SPECS.iter())
        .chain(crate::codec_utility_specs::SPECS.iter())
        .chain(crate::host_utility_specs::SPECS.iter())
        .find(|(name, _)| *name == kind)
        .map(|(_, spec)| *spec)
}
#[derive(Debug, Clone)]
pub(crate) struct Operand {
    literal: Value,
    variable: Option<String>,
    kind: ScalarKind,
}
#[derive(Debug, Clone)]
pub(crate) struct UtilityOperation {
    api: &'static str,
    operands: Vec<Operand>,
    outputs: Vec<String>,
    result: ScalarKind,
    size: bool,
}
pub(crate) fn parse(kind: &str, args: &Value) -> Result<UtilityOperation, &'static str> {
    let spec = spec(kind).ok_or("unknown utility block")?;
    let map = args
        .as_object()
        .ok_or("utility arguments must be an object")?;
    let output_fields: &[&str] = if spec.size {
        &["widthVariable", "heightVariable"]
    } else {
        &["resultVariable"]
    };
    for key in map.keys() {
        if !output_fields.contains(&key.as_str())
            && !spec
                .fields
                .iter()
                .any(|f| key == f.name || key.strip_suffix("Variable") == Some(f.name))
        {
            return Err("unknown utility argument");
        }
    }
    let operands = spec
        .fields
        .iter()
        .map(|field| {
            let literal = map.get(field.name).ok_or("utility literal is required")?;
            validate_literal(field, literal)?;
            if crate::pure_utility_specs::choices(kind, field.name)
                .is_some_and(|choices| !choices.contains(&literal.as_str().unwrap_or_default()))
            {
                return Err("unknown utility operation choice");
            }
            let variable = map
                .get(&format!("{}Variable", field.name))
                .map(|v| {
                    v.as_str()
                        .filter(|n| crate::analyze::valid_variable_name(n))
                        .map(str::to_owned)
                        .ok_or("invalid utility source variable")
                })
                .transpose()?;
            if kind.starts_with("files.")
                && matches!(field.name, "path" | "destination")
                && variable.is_none()
            {
                crate::data_operations::validate_path(
                    literal.as_str().ok_or("file path must be string")?,
                    false,
                )?;
            }
            Ok(Operand {
                literal: literal.clone(),
                variable,
                kind: field.kind,
            })
        })
        .collect::<Result<Vec<_>, &'static str>>()?;
    let outputs = output_fields
        .iter()
        .map(|field| {
            map.get(*field)
                .and_then(Value::as_str)
                .filter(|n| crate::analyze::valid_variable_name(n))
                .map(str::to_owned)
                .ok_or("utility result requires declared variable")
        })
        .collect::<Result<Vec<_>, _>>()?;
    if outputs.len() == 2 && outputs[0] == outputs[1] {
        return Err("screen width and height require distinct output variables");
    }
    Ok(UtilityOperation {
        api: spec.api,
        operands,
        outputs,
        result: spec.result,
        size: spec.size,
    })
}
impl UtilityOperation {
    pub(crate) fn validate_types(
        &self,
        lookup: impl Fn(&str) -> Option<ProjectVariableType>,
    ) -> Result<(), &'static str> {
        for operand in &self.operands {
            if operand
                .variable
                .as_deref()
                .is_some_and(|name| !operand.kind.accepts(lookup(name)))
            {
                return Err("utility source variable must be declared with matching type");
            }
        }
        for name in &self.outputs {
            let actual = lookup(name);
            let valid = match self.result {
                ScalarKind::Integer => actual == Some(ProjectVariableType::Integer),
                ScalarKind::Number => actual == Some(ProjectVariableType::Number),
                ScalarKind::String => actual == Some(ProjectVariableType::String),
            };
            if !valid {
                return Err("utility result variable must be declared with exact type");
            }
        }
        Ok(())
    }
    pub(crate) fn render(
        &self,
        quote: impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        let operands = self
            .operands
            .iter()
            .map(|o| {
                if let Some(name) = &o.variable {
                    quote(name).map(|key| format!("__vars[{key}]"))
                } else if let Some(text) = o.literal.as_str() {
                    quote(text)
                } else {
                    Ok(o.literal.to_string())
                }
            })
            .collect::<Result<Vec<_>, _>>()?
            .join(",");
        let call = format!("{}({operands})", self.api);
        let first = quote(&self.outputs[0])?;
        if self.size {
            let second = quote(&self.outputs[1])?;
            Ok(format!("do local __size={call}; __vars[{first}]=__size.width; __vars[{second}]=__size.height end"))
        } else {
            Ok(format!("__vars[{first}] = {call}"))
        }
    }
}
fn validate_literal(field: &Field, literal: &Value) -> Result<(), &'static str> {
    if field.kind == ScalarKind::String {
        let text = literal.as_str().ok_or("utility requires string")?;
        let min = field
            .min
            .to_string()
            .parse::<usize>()
            .map_err(|_| "invalid string budget")?;
        let max = field
            .max
            .to_string()
            .parse::<usize>()
            .map_err(|_| "invalid string budget")?;
        if text.len() < min || text.len() > max {
            return Err("utility text outside byte budget");
        }
        return Ok(());
    }
    literal
        .as_f64()
        .filter(|v| v.is_finite() && *v >= field.min && *v <= field.max)
        .ok_or("utility number outside finite range")?;
    if field.kind == ScalarKind::Integer && literal.as_i64().is_none() {
        return Err("utility requires exact integer");
    }
    Ok(())
}
