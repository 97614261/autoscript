use crate::{analyze::declared_variable_type, model::CompileError};
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::{json, Map, Value};

pub(crate) const KINDS: &[&str] = &[
    "screen.transform",
    "screen.savepng",
    "vision.findallmulticolor",
    "vision.components",
    "ocr.glyphdetails",
    "ocr.findtext",
    "vision.readresult",
    "vision.resultaction",
];
#[derive(Debug, Clone)]
pub(crate) struct Operation {
    pub kind: String,
    args: Map<String, Value>,
}
fn integer(args: &Map<String, Value>, name: &str, min: i64, max: i64) -> Result<i64, &'static str> {
    args.get(name)
        .and_then(Value::as_i64)
        .filter(|v| *v >= min && *v <= max)
        .ok_or("integer option is out of range")
}
fn text<'a>(args: &'a Map<String, Value>, name: &str) -> Result<&'a str, &'static str> {
    args.get(name)
        .and_then(Value::as_str)
        .filter(|v| !v.is_empty() && v.len() <= 16384)
        .ok_or("text option is missing or exceeds budget")
}
fn region(args: &Map<String, Value>) -> Result<Value, &'static str> {
    let (l, t, r, b) = (
        integer(args, "left", 0, 32768)?,
        integer(args, "top", 0, 32768)?,
        integer(args, "right", 1, 32768)?,
        integer(args, "bottom", 1, 32768)?,
    );
    if l >= r || t >= b || (r - l) * (b - t) > 4_194_304 {
        return Err("region is empty or exceeds pixel budget");
    }
    Ok(json!({"left":l,"top":t,"right":r,"bottom":b}))
}
pub(crate) fn parse(kind: &str, value: &Value) -> Result<Operation, &'static str> {
    let args = value
        .as_object()
        .ok_or("vision tool arguments must be object")?;
    let keys: &[&str] = match kind {
        "screen.transform" => &["frameVariable", "mode", "width", "height", "resultVariable"],
        "screen.savepng" => &["frameVariable", "path"],
        "vision.findallmulticolor" => &[
            "frameVariable",
            "samples",
            "left",
            "top",
            "right",
            "bottom",
            "limit",
            "resultVariable",
        ],
        "vision.components" => &[
            "frameVariable",
            "rgb",
            "tolerance",
            "connectivity",
            "minArea",
            "left",
            "top",
            "right",
            "bottom",
            "limit",
            "resultVariable",
        ],
        "ocr.glyphdetails" => &[
            "frameVariable",
            "dictionaryPath",
            "foregroundRgb",
            "tolerance",
            "similarityPermille",
            "left",
            "top",
            "right",
            "bottom",
            "spaceGapColumns",
            "resultVariable",
        ],
        "ocr.findtext" => &["detailsVariable", "text", "resultVariable"],
        "vision.readresult" => &[
            "sourceVariable",
            "shape",
            "index",
            "field",
            "resultVariable",
        ],
        "vision.resultaction" => &[
            "sourceVariable",
            "shape",
            "selection",
            "anchor",
            "action",
            "limit",
            "durationMs",
            "offsetX",
            "offsetY",
            "screenConfirmed",
        ],
        _ => return Err("unknown vision extension"),
    };
    let transform_shape = kind == "screen.transform"
        && (args.len() == keys.len() || args.len() == keys.len() + 1)
        && args
            .keys()
            .all(|key| keys.iter().any(|required| required == key) || key == "threshold");
    if (!transform_shape && args.len() != keys.len()) || keys.iter().any(|k| !args.contains_key(*k))
    {
        return Err("unexpected or missing vision options");
    }
    let op = Operation {
        kind: kind.into(),
        args: args.clone(),
    };
    op.options()?;
    Ok(op)
}

fn transform_options(a: &Map<String, Value>) -> Result<Value, &'static str> {
    let mode = text(a, "mode")?;
    let (w, h) = (
        integer(a, "width", 0, 4_194_304)?,
        integer(a, "height", 0, 4_194_304)?,
    );
    if ![
        "rotate90",
        "rotate180",
        "rotate270",
        "flipX",
        "flipY",
        "gray",
        "threshold",
        "resize",
    ]
    .contains(&mode)
        || (mode == "resize" && (w == 0 || h == 0 || w * h > 4_194_304))
        || (mode != "resize" && (w != 0 || h != 0))
    {
        return Err("invalid transform dimensions or mode");
    }
    let threshold = a.get("threshold").map_or(Ok(128), |value| {
        value
            .as_i64()
            .filter(|item| (0..=255).contains(item))
            .ok_or("threshold must be 0..255")
    })?;
    Ok(json!({"mode":mode,"width":w,"height":h,"threshold":threshold}))
}

impl Operation {
    fn options(&self) -> Result<Value, &'static str> {
        let a = &self.args;
        Ok(match self.kind.as_str() {
            "screen.transform" => transform_options(a)?,
            "screen.savepng" => {
                let path = text(a, "path")?;
                if std::path::Path::new(path)
                    .extension()
                    .is_none_or(|ext| !ext.eq_ignore_ascii_case("png"))
                    || path.len() > 256
                    || path.split('/').count() > 16
                    || path.split('/').any(|p| {
                        p.is_empty()
                            || p.starts_with('.')
                            || p.ends_with('.')
                            || p.ends_with(' ')
                            || p.chars()
                                .any(|c| c.is_control() || "\\:*?\"<>|".contains(c))
                    })
                {
                    return Err("invalid private PNG path");
                }
                json!(path)
            }
            "vision.findallmulticolor" => {
                let samples: Value =
                    serde_json::from_str(text(a, "samples")?).map_err(|_| "invalid sample JSON")?;
                let items = samples
                    .as_array()
                    .filter(|v| !v.is_empty() && v.len() <= 64)
                    .ok_or("pattern needs 1..64 samples")?;
                for item in items {
                    let s = item.as_object().ok_or("sample must be object")?;
                    if s.len() != 4 {
                        return Err("sample needs only dx/dy/rgb/tolerance");
                    }
                    integer(s, "dx", -32768, 32768)?;
                    integer(s, "dy", -32768, 32768)?;
                    integer(s, "rgb", 0, 16_777_215)?;
                    integer(s, "tolerance", 0, 255)?;
                }
                let region = region(a)?;
                let pixels = (a["right"].as_i64().unwrap() - a["left"].as_i64().unwrap())
                    * (a["bottom"].as_i64().unwrap() - a["top"].as_i64().unwrap());
                if pixels * i64::try_from(items.len()).expect("bounded samples") > 100_000_000 {
                    return Err("pattern exceeds comparison budget");
                }
                json!({"region":region,"samples":samples,"limit":integer(a,"limit",1,256)?})
            }
            "vision.components" => {
                let connectivity = integer(a, "connectivity", 4, 8)?;
                if ![4, 8].contains(&connectivity) {
                    return Err("connectivity must be 4 or 8");
                }
                json!({"region":region(a)?,"rgb":integer(a,"rgb",0,16_777_215)?,"tolerance":integer(a,"tolerance",0,255)?,"connectivity":connectivity,"min_area":integer(a,"minArea",1,4_194_304)?,"limit":integer(a,"limit",1,256)?})
            }
            "ocr.glyphdetails" => {
                region(a)?;
                integer(a, "foregroundRgb", 0, 16_777_215)?;
                integer(a, "tolerance", 0, 255)?;
                integer(a, "similarityPermille", 0, 1000)?;
                integer(a, "spaceGapColumns", 0, 65535)?;
                validate_dictionary_path(text(a, "dictionaryPath")?)?;
                json!(null)
            }
            "ocr.findtext" => {
                let target = text(a, "text")?;
                if target.len() > 256 || target.chars().any(char::is_whitespace) {
                    return Err("target text must be 1..256 bytes without whitespace");
                }
                json!(target)
            }
            "vision.resultaction" => {
                if !["IMAGES", "GLYPH_ITEM", "TEXT_ITEM", "COLORS", "COMPONENTS"]
                    .contains(&text(a, "shape")?)
                    || !["first", "all"].contains(&text(a, "selection")?)
                    || !["center", "topLeft"].contains(&text(a, "anchor")?)
                    || !["tap", "hold", "tapWait", "pressRelease"].contains(&text(a, "action")?)
                    || a["screenConfirmed"].as_bool() != Some(true)
                    || (a["action"] == "hold" && a["selection"] != "first")
                {
                    return Err("confirm screen coordinates and valid action; hold only supports first result");
                }
                integer(a, "limit", 1, 64)?;
                integer(a, "durationMs", 1, 5000)?;
                integer(a, "offsetX", -32768, 32768)?;
                integer(a, "offsetY", -32768, 32768)?;
                json!(null)
            }
            "vision.readresult" => result_options(a)?,
            _ => unreachable!(),
        })
    }
    pub(crate) fn validate(
        &self,
        manifest: &ProjectManifest,
        flow: &str,
    ) -> Result<(), &'static str> {
        let source = if matches!(
            self.kind.as_str(),
            "vision.readresult" | "vision.resultaction"
        ) {
            "sourceVariable"
        } else if self.kind == "ocr.findtext" {
            "detailsVariable"
        } else {
            "frameVariable"
        };
        let kind = if matches!(
            self.kind.as_str(),
            "ocr.findtext" | "vision.readresult" | "vision.resultaction"
        ) {
            ProjectVariableType::String
        } else {
            ProjectVariableType::Image
        };
        if declared_variable_type(manifest, flow, text(&self.args, source)?) != Some(kind) {
            return Err("source variable type does not match");
        }
        if !matches!(self.kind.as_str(), "screen.savepng" | "vision.resultaction") {
            let result = text(&self.args, "resultVariable")?;
            let kind = if self.kind == "vision.readresult"
                && !matches!(self.args["field"].as_str(), Some("text" | "path"))
            {
                ProjectVariableType::Integer
            } else if self.kind == "screen.transform" {
                ProjectVariableType::Image
            } else {
                ProjectVariableType::String
            };
            if declared_variable_type(manifest, flow, result) != Some(kind)
                || result == text(&self.args, source)?
            {
                return Err("output must be a different declared variable with matching type");
            }
        }
        if self.kind == "ocr.glyphdetails"
            && !manifest.resources.iter().any(|r| {
                r.kind == flow_ir::ProjectResourceKind::GlyphDictionary
                    && r.path == self.args["dictionaryPath"].as_str().unwrap_or_default()
            })
        {
            return Err("dictionary must be declared");
        }
        Ok(())
    }
    pub(crate) fn capability(&self) -> &'static str {
        match self.kind.as_str() {
            "screen.transform" => "screen.capture",
            "screen.savepng" => "storage.data",
            "vision.readresult" => "core.task",
            "vision.resultaction" => "input.basic",
            "ocr.glyphdetails" | "ocr.findtext" => "ocr.glyph",
            _ => "vision.pixel",
        }
    }
    pub(crate) fn render(
        &self,
        quote: impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        let a = &self.args;
        let get = |key: &str| quote(a[key].as_str().unwrap_or_default());
        let source = get(
            if matches!(
                self.kind.as_str(),
                "vision.readresult" | "vision.resultaction"
            ) {
                "sourceVariable"
            } else if self.kind == "ocr.findtext" {
                "detailsVariable"
            } else {
                "frameVariable"
            },
        )?;
        let options = self.options().expect("validated options");
        if self.kind == "screen.savepng" {
            return Ok(format!("Screen.savePng(__vars[{source}],{})", get("path")?));
        }
        if self.kind == "vision.resultaction" {
            let shape = a["shape"].as_str().unwrap_or_default();
            let center = a["anchor"] == "center" && shape != "COLORS";
            let x = if center {
                "centerX"
            } else if matches!(shape, "IMAGES" | "COLORS") {
                "x"
            } else {
                "left"
            };
            let y = if center {
                "centerY"
            } else if matches!(shape, "IMAGES" | "COLORS") {
                "y"
            } else {
                "top"
            };
            let max = if a["selection"] == "first" {
                1
            } else {
                a["limit"].as_i64().unwrap_or(1)
            };
            let action = match a["action"].as_str().unwrap_or_default() {
                "tap" => "Input.tapScreen(__p[1],__p[2]);".into(),
                "hold" => "Input.pointerDownScreen(__p[1],__p[2]);".into(),
                "tapWait" => format!(
                    "Input.tapScreen(__p[1],__p[2]);Task.sleep({});",
                    a["durationMs"]
                ),
                _ => format!(
                    "Input.pointerDownScreen(__p[1],__p[2]);Task.sleep({});Input.pointerUp();",
                    a["durationMs"]
                ),
            };
            return Ok(format!("do local __r=__vars[{source}];local __s={};local __n=math.min(assert(tonumber(Screen.recognitionValue(__r,__s,0,'count'))),{max});local __size=System.getScreenSize();local __points={{}};for __i=1,__n do if __s~='GLYPH_ITEM' or tonumber(Screen.recognitionValue(__r,__s,__i,'recognized'))==1 then local __x=assert(tonumber(Screen.recognitionValue(__r,__s,__i,'{x}')))+({});local __y=assert(tonumber(Screen.recognitionValue(__r,__s,__i,'{y}')))+({});assert(__x>=0 and __y>=0 and __x<__size.width and __y<__size.height,'RESULT_ACTION_OUT_OF_BOUNDS');__points[#__points+1]={{__x,__y}} end end;for __i=1,#__points do local __p=__points[__i];{action}end end",get("shape")?,a["offsetX"],a["offsetY"]));
        }
        let output = get("resultVariable")?;
        if self.kind == "vision.readresult" {
            let call = format!(
                "Screen.recognitionValue(__vars[{source}],{},{},{})",
                get("shape")?,
                a["index"],
                get("field")?
            );
            let value = if matches!(a["field"].as_str(), Some("text" | "path")) {
                format!("Data.text({call})")
            } else {
                format!("assert(tonumber({call}))")
            };
            return Ok(format!("__vars[{output}]={value}"));
        }
        if self.kind == "ocr.findtext" {
            return Ok(format!(
                "__vars[{output}]=Ocr.findText(__vars[{source}],{})",
                get("text")?
            ));
        }
        if self.kind == "ocr.glyphdetails" {
            return Ok(format!("do local __dictionary=Ocr.loadDictionary({}); __vars[{output}]=Ocr.glyphDetails(__vars[{source}],__dictionary,{},{},{},{},{},{},{},{}); Ocr.releaseDictionary(__dictionary) end",get("dictionaryPath")?,a["foregroundRgb"],a["tolerance"],a["similarityPermille"],a["left"],a["top"],a["right"],a["bottom"],a["spaceGapColumns"]));
        }
        let api = match self.kind.as_str() {
            "screen.transform" => "Screen.transform",
            "vision.components" => "Screen.components",
            _ => "Screen.findAllMultiColor",
        };
        Ok(format!(
            "__vars[{output}]={api}(__vars[{source}],{})",
            quote(&options.to_string())?
        ))
    }
}

fn validate_dictionary_path(path: &str) -> Result<(), &'static str> {
    if path.len() > 256 || !path.starts_with("dictionaries/") || !path.ends_with(".asglyph") {
        Err("invalid dictionary path")
    } else {
        Ok(())
    }
}

fn result_options(a: &Map<String, Value>) -> Result<Value, &'static str> {
    let shape = text(a, "shape")?;
    let field = text(a, "field")?;
    let fields: &[&str] = match shape {
        "IMAGES" => &[
            "count",
            "truncated",
            "x",
            "y",
            "width",
            "height",
            "scorePermille",
            "path",
            "centerX",
            "centerY",
        ],
        "GLYPH_SUMMARY" => &["count", "text", "coveragePermille", "averageScorePermille"],
        "GLYPH_ITEM" => &[
            "text",
            "left",
            "top",
            "right",
            "bottom",
            "score",
            "recognized",
            "centerX",
            "centerY",
        ],
        "TEXT_ITEM" => &[
            "count", "text", "left", "top", "right", "bottom", "centerX", "centerY",
        ],
        "COLORS" => &["count", "x", "y"],
        "COMPONENTS" => &[
            "count", "area", "left", "top", "right", "bottom", "centerX", "centerY",
        ],
        _ => return Err("invalid recognition result shape"),
    };
    let index = integer(a, "index", 0, 2048)?;
    let aggregate = shape == "GLYPH_SUMMARY" || matches!(field, "count" | "truncated");
    let limit = match shape {
        "IMAGES" => 64,
        "COLORS" | "COMPONENTS" => 256,
        _ => 2048,
    };
    if !fields.contains(&field)
        || (aggregate && index != 0)
        || (!aggregate && index == 0)
        || index > limit
    {
        return Err("invalid recognition result field or index");
    }
    Ok(json!(null))
}
