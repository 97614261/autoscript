//! Explicit visual resource operations reuse the task-owned frame pool.
use crate::{analyze::declared_variable_type, model::CompileError};
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::{Map, Value};

pub(crate) const KINDS: &[&str] = &[
    "screen.loadimage",
    "screen.crop",
    "screen.series",
    "screen.seriesframe",
    "screen.seriesrelease",
];
#[derive(Debug, Clone)]
pub(crate) enum Operation {
    LoadImage {
        path: String,
        output: String,
    },
    Crop {
        source: String,
        output: String,
        bounds: [i64; 4],
    },
    Series {
        output: String,
        count: i64,
        duration: i64,
        fps: i64,
        partial: bool,
    },
    Frame {
        source: String,
        output: String,
        index: i64,
    },
    Release {
        source: String,
    },
}
fn name(map: &Map<String, Value>, key: &str) -> Result<String, &'static str> {
    map.get(key)
        .and_then(Value::as_str)
        .filter(|v| crate::analyze::valid_variable_name(v))
        .map(str::to_owned)
        .ok_or("resource variable is required")
}
fn integer(map: &Map<String, Value>, key: &str, min: i64, max: i64) -> Result<i64, &'static str> {
    map.get(key)
        .and_then(Value::as_i64)
        .filter(|v| (min..=max).contains(v))
        .ok_or("resource argument outside integer range")
}
pub(crate) fn parse(kind: &str, args: &Value) -> Result<Operation, &'static str> {
    let map = args
        .as_object()
        .ok_or("resource arguments must be object")?;
    let keys: &[&str] = match kind {
        "screen.loadimage" => &["imagePath", "resultVariable"],
        "screen.crop" => &[
            "frameVariable",
            "resultVariable",
            "left",
            "top",
            "right",
            "bottom",
        ],
        "screen.series" => &[
            "resultVariable",
            "maxFrames",
            "durationMs",
            "targetFps",
            "allowPartial",
        ],
        "screen.seriesframe" => &["sourceVariable", "resultVariable", "index"],
        "screen.seriesrelease" => &["sourceVariable"],
        _ => return Err("unknown resource operation"),
    };
    if map.len() != keys.len() || keys.iter().any(|key| !map.contains_key(*key)) {
        return Err("resource fields are incomplete or unknown");
    }
    match kind {
        "screen.loadimage" => {
            let path = map
                .get("imagePath")
                .and_then(Value::as_str)
                .ok_or("image path is required")?;
            if path.len() > 512
                || !path.starts_with("assets/images/")
                || path.contains('\\')
                || path
                    .split('/')
                    .any(|part| part.is_empty() || part == "." || part == "..")
            {
                return Err("image path must be a canonical project image");
            }
            Ok(Operation::LoadImage {
                path: path.into(),
                output: name(map, "resultVariable")?,
            })
        }
        "screen.crop" => {
            let source = name(map, "frameVariable")?;
            let output = name(map, "resultVariable")?;
            let bounds = [
                integer(map, "left", 0, 32768)?,
                integer(map, "top", 0, 32768)?,
                integer(map, "right", 1, 32768)?,
                integer(map, "bottom", 1, 32768)?,
            ];
            if source == output || bounds[0] >= bounds[2] || bounds[1] >= bounds[3] {
                return Err("crop requires distinct variables and nonempty rectangle");
            }
            Ok(Operation::Crop {
                source,
                output,
                bounds,
            })
        }
        "screen.series" => Ok(Operation::Series {
            output: name(map, "resultVariable")?,
            count: integer(map, "maxFrames", 1, 120)?,
            duration: integer(map, "durationMs", 1, 60000)?,
            fps: integer(map, "targetFps", 1, 120)?,
            partial: map
                .get("allowPartial")
                .and_then(Value::as_bool)
                .ok_or("allowPartial must be boolean")?,
        }),
        "screen.seriesframe" => Ok(Operation::Frame {
            source: name(map, "sourceVariable")?,
            output: name(map, "resultVariable")?,
            index: integer(map, "index", 1, 120)?,
        }),
        "screen.seriesrelease" => Ok(Operation::Release {
            source: name(map, "sourceVariable")?,
        }),
        _ => unreachable!(),
    }
}
impl Operation {
    pub(crate) fn validate(
        &self,
        manifest: &ProjectManifest,
        flow: &str,
    ) -> Result<(), &'static str> {
        let fields: Vec<(&str, ProjectVariableType)> = match self {
            Self::LoadImage { path, output } => {
                if manifest
                    .flows
                    .iter()
                    .find(|item| item.flow_id == flow)
                    .is_some_and(|item| {
                        item.params
                            .iter()
                            .any(|parameter| parameter.name.as_str() == output.as_str())
                    })
                {
                    return Err("image output must not be shadowed by a flow parameter");
                }
                if !manifest.resources.iter().any(|resource| {
                    resource.kind == flow_ir::ProjectResourceKind::Image && resource.path == *path
                }) {
                    return Err("image resource must be declared");
                }
                vec![(output, ProjectVariableType::Image)]
            }
            Self::Crop { source, output, .. } => vec![
                (source, ProjectVariableType::Image),
                (output, ProjectVariableType::Image),
            ],
            Self::Series { output, .. } => vec![(output, ProjectVariableType::String)],
            Self::Frame { source, output, .. } => vec![
                (source, ProjectVariableType::String),
                (output, ProjectVariableType::Image),
            ],
            Self::Release { source } => vec![(source, ProjectVariableType::String)],
        };
        if fields
            .iter()
            .any(|(name, kind)| declared_variable_type(manifest, flow, name) != Some(*kind))
        {
            return Err("frame resource variables must be declared with matching type");
        }
        Ok(())
    }
    pub(crate) fn render(
        &self,
        quote: impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        Ok(match self {
            Self::LoadImage {path,output} => format!("__vars[{}]=Screen.loadImage({})",quote(output)?,quote(path)?),
            Self::Crop {source,output,bounds:[l,t,r,b]}=>format!("__vars[{}]=Screen.crop(__vars[{}],{l},{t},{r},{b})",quote(output)?,quote(source)?),
            Self::Series {output,count,duration,fps,partial}=>format!("do local __series=Screen.captureSeries({count},{duration},{fps},{partial}); local __parts={{}}; for __i,__sample in ipairs(__series.frames) do __parts[__i]=string.format('{{\"frame\":%d,\"timestampNanos\":%d}}',__sample.frame,__sample.timestampNanos) end; __vars[{}]='{{\"frames\":['..table.concat(__parts,',')..'],\"droppedFrames\":'..tostring(__series.droppedFrames)..',\"partial\":'..tostring(__series.partial)..'}}' end",quote(output)?),
            Self::Frame {source,output,index}=>format!("do local __frames=Data.getJson(__vars[{}],'frames'); local __sample=Data.getJson(__frames,'{index}'); __vars[{}]=assert(math.tointeger(tonumber(Data.get(__sample,'frame'))),'系列帧不存在') end",quote(source)?,quote(output)?),
            Self::Release {source}=> {
                let key=quote(source)?;
                format!("do local __frames=Data.getJson(__vars[{key}],'frames'); local __n=Data.length(__frames); assert(__n<=120,'系列超过上限'); for __i=1,__n do local __sample=Data.getJson(__frames,tostring(__i)); Screen.release(assert(math.tointeger(tonumber(Data.get(__sample,'frame'))))) end; __vars[{key}]='' end")
            }
        })
    }
}
