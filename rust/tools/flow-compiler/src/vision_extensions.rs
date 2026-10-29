use crate::{analyze::declared_variable_type, model::CompileError};
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::{json, Map, Value};

pub(crate) const KINDS: &[&str] = &[
    "vision.findfeatures",
    "ocr.alphanumericv5",
    "screen.transform",
    "screen.compareframes",
    "screen.mappoint",
    "vision.findscaled",
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

fn glyph_options(a: &Map<String, Value>) -> Result<Value, &'static str> {
    region(a)?;
    let primary = u32::try_from(integer(a, "foregroundRgb", 0, 16_777_215)?)
        .map_err(|_| "invalid glyph RGB")?;
    if let Some(value) = a.get("additionalColors") {
        let colors =
            glyph_ocr::parse_additional_colors(value.as_str().ok_or("glyph colors must be text")?)?;
        glyph_ocr::validate_additional_colors(primary, &colors)?;
        let pixels = (integer(a, "right", 1, 32768)? - integer(a, "left", 0, 32768)?)
            * (integer(a, "bottom", 1, 32768)? - integer(a, "top", 0, 32768)?);
        if u64::try_from(pixels).map_err(|_| "invalid glyph region")? * (colors.len() as u64 + 1)
            > glyph_ocr::MAX_COLOR_COMPARISONS
        {
            return Err("glyph color work exceeds budget");
        }
    }
    integer(a, "tolerance", 0, 255)?;
    integer(a, "similarityPermille", 0, 1000)?;
    integer(a, "spaceGapColumns", 0, 65535)?;
    validate_dictionary_path(text(a, "dictionaryPath")?)?;
    Ok(json!(null))
}

fn render_glyph_details(
    a: &Map<String, Value>,
    source: &str,
    output: &str,
    quote: &impl Fn(&str) -> Result<String, CompileError>,
) -> Result<String, CompileError> {
    let extra = a
        .get("additionalColors")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(|s| quote(s).map(|s| format!(",{s}")))
        .transpose()?
        .unwrap_or_default();
    let guard = if extra.is_empty() {
        ""
    } else {
        "if Ocr.glyphPaletteVersion ~= 1 then error('GLYPH_PALETTE_UNSUPPORTED') end; "
    };
    Ok(format!("do {guard}local __dictionary=Ocr.loadDictionary({}); __vars[{output}]=Ocr.glyphDetails(__vars[{source}],__dictionary,{},{},{},{},{},{},{},{}{extra}); Ocr.releaseDictionary(__dictionary) end",quote(a["dictionaryPath"].as_str().unwrap_or_default())?,a["foregroundRgb"],a["tolerance"],a["similarityPermille"],a["left"],a["top"],a["right"],a["bottom"],a["spaceGapColumns"]))
}
const FEATURE_FIELDS: &[&str] = &[
    "frameVariable",
    "imagePath",
    "left",
    "top",
    "right",
    "bottom",
    "minimumScorePermille",
    "resultVariable",
];
const V5_FIELDS: &[&str] = &[
    "frameVariable",
    "left",
    "top",
    "right",
    "bottom",
    "minimumScorePermille",
    "resultVariable",
];
fn argument_keys(kind: &str) -> Result<&'static [&'static str], &'static str> {
    Ok(match kind {
        "vision.findfeatures" => FEATURE_FIELDS,
        "ocr.alphanumericv5" => V5_FIELDS,
        "screen.transform" => &["frameVariable", "mode", "width", "height", "resultVariable"],
        "screen.compareframes" => &[
            "frameVariable",
            "previousFrameVariable",
            "left",
            "top",
            "right",
            "bottom",
            "tolerance",
            "resultVariable",
        ],
        "screen.mappoint" => &[
            "frameVariable",
            "x",
            "y",
            "xVariable",
            "yVariable",
            "resultVariable",
        ],
        "screen.savepng" => &["frameVariable", "path"],
        "vision.findscaled" => &[
            "frameVariable",
            "imagePath",
            "left",
            "top",
            "right",
            "bottom",
            "tolerance",
            "similarityPermille",
            "direction",
            "minScalePermille",
            "maxScalePermille",
            "stepPermille",
            "resultVariable",
        ],
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
    })
}
pub(crate) fn parse(kind: &str, value: &Value) -> Result<Operation, &'static str> {
    let args = value
        .as_object()
        .ok_or("vision tool arguments must be object")?;
    let keys = argument_keys(kind)?;
    let transform_shape = kind == "screen.transform"
        && (keys.len()..=keys.len() + 4).contains(&args.len())
        && args.keys().all(|key| {
            keys.iter().any(|required| required == key)
                || matches!(key.as_str(), "threshold" | "filter" | "mask" | "cleanup")
        });
    let glyph_shape = kind == "ocr.glyphdetails"
        && (keys.len()..=keys.len() + 1).contains(&args.len())
        && args
            .keys()
            .all(|key| keys.contains(&key.as_str()) || key == "additionalColors");
    if (kind == "screen.transform" && !transform_shape)
        || (kind != "screen.transform" && !glyph_shape && args.len() != keys.len())
        || keys.iter().any(|k| !args.contains_key(*k))
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
        "filter",
        "mask",
        "despeckle",
        "erode",
        "dilate",
        "open",
        "close",
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
    let mut options = json!({"mode":mode,"width":w,"height":h,"threshold":threshold});
    if mode == "filter" {
        let filter = pixel_vision::color_filter::Filter::parse(text(a, "filter")?)?;
        options["filter"] =
            serde_json::to_value(filter).map_err(|_| "invalid filter serialization")?;
    } else if a.contains_key("filter") {
        return Err("filter rules require filter mode");
    }
    if mode == "mask" {
        let mask = pixel_vision::image_processing::Mask::parse(text(a, "mask")?)?;
        options["mask"] = serde_json::to_value(mask).map_err(|_| "invalid mask serialization")?;
    } else if a.contains_key("mask") {
        return Err("mask rules require mask mode");
    }
    if matches!(mode, "despeckle" | "erode" | "dilate" | "open" | "close") {
        let cleanup = pixel_vision::image_processing::Cleanup::parse(text(a, "cleanup")?)?;
        options["cleanup"] =
            serde_json::to_value(cleanup).map_err(|_| "invalid cleanup serialization")?;
    } else if a.contains_key("cleanup") {
        return Err("cleanup rules require cleanup mode");
    }
    if options.to_string().len() > 16384 {
        return Err("transform options exceed byte budget");
    }
    Ok(options)
}

impl Operation {
    fn options(&self) -> Result<Value, &'static str> {
        let a = &self.args;
        Ok(match self.kind.as_str() {
            "vision.findfeatures" | "ocr.alphanumericv5" => {
                let r = region(a)?;
                integer(a, "minimumScorePermille", 0, 1000)?;
                if self.kind == "vision.findfeatures"
                    && (a["right"].as_i64().unwrap() - a["left"].as_i64().unwrap())
                        * (a["bottom"].as_i64().unwrap() - a["top"].as_i64().unwrap())
                        > 1_048_576
                {
                    return Err("feature ROI exceeds 1M pixels");
                }
                r
            }
            "screen.transform" => transform_options(a)?,
            "screen.savepng" => private_png_options(a)?,
            "screen.compareframes" => {
                json!({"region":region(a)?,"tolerance":integer(a,"tolerance",0,255)?})
            }
            "screen.mappoint" => {
                integer(a, "x", 0, 32768)?;
                integer(a, "y", 0, 32768)?;
                for key in ["xVariable", "yVariable"] {
                    if a[key].as_str().is_none() {
                        return Err("coordinate variable must be text");
                    }
                }
                json!(null)
            }
            "vision.findscaled" => scaled_options(a)?,
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
            "ocr.glyphdetails" => glyph_options(a)?,
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
        if self.kind == "screen.compareframes"
            && (declared_variable_type(manifest, flow, text(&self.args, "previousFrameVariable")?)
                != Some(ProjectVariableType::Image)
                || self.args["frameVariable"] == self.args["previousFrameVariable"])
        {
            return Err("comparison requires two distinct image variables");
        }
        if matches!(
            self.kind.as_str(),
            "vision.findscaled" | "vision.findfeatures"
        ) && !manifest.resources.iter().any(|r| {
            r.kind == flow_ir::ProjectResourceKind::Image
                && Some(r.path.as_str()) == self.args["imagePath"].as_str()
        }) {
            return Err("scaled template must be declared image");
        }
        if self.kind == "screen.mappoint" {
            for key in ["xVariable", "yVariable"] {
                let name = self.args[key].as_str().unwrap_or_default();
                if !name.is_empty()
                    && declared_variable_type(manifest, flow, name)
                        != Some(ProjectVariableType::Integer)
                {
                    return Err("coordinates require declared integer variables");
                }
            }
        }
        Ok(())
    }
    pub(crate) fn capability(&self) -> &'static str {
        match self.kind.as_str() {
            "vision.findfeatures" => "vision.opencv",
            "ocr.alphanumericv5" => "ocr.onnx",
            "screen.transform" | "screen.mappoint" => "screen.capture",
            "screen.savepng" => "storage.data",
            "vision.findscaled" => "vision.template",
            "vision.readresult" => "core.task",
            "vision.resultaction" => "input.basic",
            "ocr.glyphdetails" | "ocr.findtext" => "ocr.glyph",
            _ => "vision.pixel",
        }
    }
    fn render_native_extension(
        &self,
        quote: &impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        let args = &self.args;
        let get = |key: &str| quote(args[key].as_str().unwrap_or_default());
        let source = get("frameVariable")?;
        let output = get("resultVariable")?;
        let score = args["minimumScorePermille"].as_i64().unwrap();
        let left = args["left"].as_i64().unwrap();
        let top = args["top"].as_i64().unwrap();
        let right = args["right"].as_i64().unwrap();
        let bottom = args["bottom"].as_i64().unwrap();
        Ok(if self.kind == "vision.findfeatures" {
            format!("do local __template=Screen.loadImage({});local __ok,__matches=pcall(Screen.findFeatures,__vars[{source}],__template,{score},{left},{top},{right},{bottom});Screen.release(__template);if not __ok then error(__matches) end;__vars[{output}]=__matches end",get("imagePath")?)
        } else {
            format!("__vars[{output}]=Ocr.alphanumericV5(__vars[{source}],{left},{top},{right},{bottom},{score})")
        })
    }
    pub(crate) fn render(
        &self,
        quote: impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        if matches!(
            self.kind.as_str(),
            "vision.findfeatures" | "ocr.alphanumericv5"
        ) {
            return self.render_native_extension(&quote);
        }
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
        if let Some(query) = self.render_frame_query(&source, &output, &options, &quote)? {
            return Ok(query);
        }
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
            return render_glyph_details(a, &source, &output, &quote);
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

impl Operation {
    fn render_frame_query(
        &self,
        source: &str,
        output: &str,
        options: &Value,
        quote: &impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<Option<String>, CompileError> {
        let a = &self.args;
        let get = |key: &str| quote(a[key].as_str().unwrap_or_default());
        Ok(Some(match self.kind.as_str() {
            "vision.findscaled"=>format!("do local __template=Screen.loadImage({});local __ok,__matches=pcall(Screen.findScaled,__vars[{source}],__template,{});Screen.release(__template);if not __ok then error(__matches) end;__vars[{output}]=__matches end",get("imagePath")?,quote(&options.to_string())?),
            "screen.compareframes"=>format!("__vars[{output}]=Screen.compareFrames(__vars[{source}],__vars[{}],{})",get("previousFrameVariable")?,quote(&options.to_string())?),
            "screen.mappoint"=>{
                let coordinate=|fixed:&str,variable:&str|->Result<String,CompileError> {if a[variable].as_str()==Some("") {Ok(a[fixed].to_string())} else {Ok(format!("__vars[{}]",get(variable)?))}};
                format!("__vars[{output}]=Screen.mapPoint(__vars[{source}],{},{})",coordinate("x","xVariable")?,coordinate("y","yVariable")?)
            }
            _=>return Ok(None),
        }))
    }
}

fn private_png_options(a: &Map<String, Value>) -> Result<Value, &'static str> {
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
    Ok(json!(path))
}
fn scaled_options(a: &Map<String, Value>) -> Result<Value, &'static str> {
    let number = |key, min, max| {
        u32::try_from(integer(a, key, min, max)?).map_err(|_| "invalid scale value")
    };
    let range = pixel_vision::scaled_search::ScaleRange {
        min_permille: number("minScalePermille", 250, 4000)?,
        max_permille: number("maxScalePermille", 250, 4000)?,
        step_permille: number("stepPermille", 1, 1000)?,
    };
    range
        .values()
        .map_err(|_| "invalid scale range or more than 16 scales")?;
    Ok(
        json!({"region":region(a)?,"tolerance":integer(a,"tolerance",0,255)?,"similarityPermille":integer(a,"similarityPermille",0,1000)?,"direction":integer(a,"direction",0,4)?,
        "range":{"minPermille":range.min_permille,"maxPermille":range.max_permille,"stepPermille":range.step_permille}}),
    )
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
        "SCALED_IMAGE" => &[
            "count",
            "x",
            "y",
            "width",
            "height",
            "scorePermille",
            "scalePermille",
            "centerX",
            "centerY",
        ],
        "FRAME_DIFF" => &[
            "changedPixels",
            "totalPixels",
            "ratioPermille",
            "fresh",
            "elapsedUs",
        ],
        "SCREEN_POINT" => &["x", "y", "screenWidth", "screenHeight"],
        "COMPONENTS" => &[
            "count", "area", "left", "top", "right", "bottom", "centerX", "centerY",
        ],
        _ => return Err("invalid recognition result shape"),
    };
    let index = integer(a, "index", 0, 2048)?;
    let aggregate = matches!(shape, "GLYPH_SUMMARY" | "FRAME_DIFF" | "SCREEN_POINT")
        || matches!(field, "count" | "truncated");
    let limit = match shape {
        "IMAGES" => 64,
        "SCALED_IMAGE" => 1,
        "COMPONENTS" => 256,
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
