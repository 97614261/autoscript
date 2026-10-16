use flow_ir::{NodeRandomization, ProjectManifest, RandomizationMode};

pub(crate) fn supports(kind: &str) -> bool {
    matches!(
        kind,
        "input.tap"
            | "input.swipe"
            | "input.pointerdown"
            | "task.sleep"
            | "vision.findimage"
            | "vision.findallimages"
            | "vision.findgray"
            | "ocr.alphanumeric"
    )
}
pub(crate) fn invalid_node(node: &flow_ir::FlowNode) -> Option<&'static str> {
    if node
        .envelope
        .randomization
        .as_ref()
        .is_some_and(|policy| !policy.valid())
    {
        Some("invalid node randomization")
    } else if node.envelope.randomization.is_some() && !supports(&node.envelope.kind) {
        Some("randomization is only supported on touch, recognition-action and wait nodes")
    } else {
        None
    }
}
pub(crate) fn enabled(project: &super::analyze::PreparedProject<'_>) -> bool {
    project.manifest.randomization.active()
        || project.flows.iter().any(|flow| {
            flow.document
                .nodes
                .iter()
                .any(|node| node.envelope.randomization.is_some())
        })
}
pub(crate) fn policy(
    manifest: &ProjectManifest,
    kind: &str,
    node: Option<&NodeRandomization>,
) -> String {
    let position = if kind.starts_with("input.") {
        manifest.randomization.touch
    } else {
        manifest.randomization.recognition
    };
    let (position, wait) = match node {
        Some(node) if node.mode == RandomizationMode::Custom => (node.position, node.wait),
        Some(node) if node.mode == RandomizationMode::Disabled => (
            flow_ir::PositionRandomization::default(),
            flow_ir::WaitRandomization::default(),
        ),
        _ => (position, manifest.randomization.wait),
    };
    let unit = if wait.unit == flow_ir::WaitRandomizationUnit::Percent {
        "percent"
    } else {
        "milliseconds"
    };
    format!(
        "__random_set({},{},{},{},{},'{}')",
        if position.enabled { position.x_px } else { 0 },
        if position.enabled { position.y_px } else { 0 },
        wait.enabled,
        wait.min,
        wait.max,
        unit
    )
}

/// Infer only uninterrupted literal-coordinate gestures; never guess across control flow.
pub(crate) fn gesture_bounds(
    project: &super::analyze::PreparedProject<'_>,
) -> std::collections::HashMap<String, [i64; 4]> {
    let mut result = std::collections::HashMap::new();
    for flow in &project.flows {
        let mut blocks: std::collections::HashMap<&str, Vec<&flow_ir::FlowNode>> =
            std::collections::HashMap::new();
        for node in &flow.document.nodes {
            blocks
                .entry(&node.envelope.block_id)
                .or_default()
                .push(node);
        }
        let disabled = flow.document.disabled_node_ids();
        for mut nodes in blocks.into_values() {
            nodes.sort_by(|a, b| a.envelope.order_key.cmp(&b.envelope.order_key));
            let mut path: Option<(String, [i64; 4])> = None;
            for node in nodes {
                if disabled.contains(node.envelope.node_id.as_str()) {
                    continue;
                }
                let point = || {
                    node.envelope
                        .args
                        .get("x")
                        .and_then(serde_json::Value::as_i64)
                        .zip(
                            node.envelope
                                .args
                                .get("y")
                                .and_then(serde_json::Value::as_i64),
                        )
                };
                match node.envelope.kind.as_str() {
                    "input.pointerdown" => {
                        path = point().map(|(x, y)| {
                            (
                                format!("{}:{}", flow.declaration.flow_id, node.envelope.node_id),
                                [x, y, x, y],
                            )
                        });
                    }
                    "input.pointermove" => {
                        if let Some((_, bounds)) = &mut path {
                            if let Some((x, y)) = point() {
                                *bounds = [
                                    bounds[0].min(x),
                                    bounds[1].min(y),
                                    bounds[2].max(x),
                                    bounds[3].max(y),
                                ];
                            } else {
                                path = None;
                            }
                        }
                    }
                    "input.pointerup" => {
                        if let Some((id, bounds)) = path.take() {
                            result.insert(id, bounds);
                        }
                    }
                    "task.sleep" | "task.comment" | "task.noop" => {}
                    _ => path = None,
                }
            }
        }
    }
    result
}

// Input is a lexical facade, not a mutation of the runtime's public API table.
// Weak coroutine keys isolate gestures and allow completed/cancelled tasks to be collected.
pub(crate) const LUA: &str = r"
local __raw_input = Input
local Input = setmetatable({}, {__index=__raw_input})
local __random_states = setmetatable({}, {__mode='k'})
local function __random_state()
  local co=coroutine.running(); local state=__random_states[co]
  if not state then state={}; __random_states[co]=state end
  return state
end
local function __random_set(x,y,wait,minimum,maximum,unit)
  __random_state().policy={x=x,y=y,wait=wait,min=minimum,max=maximum,unit=unit}
  __random_state().bounds=nil
end
local function __random_bounds(x1,y1,x2,y2) __random_state().bounds={x1,y1,x2,y2} end
local function __random_range(a,b)
  if a>b then error('RANDOMIZATION_NO_VALID_OFFSET',0) end
  if a==b then return a end
  return math.random(a,b)
end
local function __random_offsets(p,q,policy)
  q=q or p
  if p.snapshotId~=q.snapshotId then error('RANDOMIZATION_COORDINATES_CHANGED',0) end
  return __random_range(math.max(-policy.x,-math.min(p.x,q.x)),math.min(policy.x,p.width-1-math.max(p.x,q.x))),
         __random_range(math.max(-policy.y,-math.min(p.y,q.y)),math.min(policy.y,p.height-1-math.max(p.y,q.y)))
end
local function __random_point(x,y,raw)
  return __raw_input.resolvePoint(x,y,raw)
end
local function __random_tap(x,y,raw)
  local policy=__random_state().policy or {x=0,y=0}
  if policy.x==0 and policy.y==0 then return (raw and __raw_input.tapScreen or __raw_input.tap)(x,y) end
  local point=__random_point(x,y,raw); local dx,dy=__random_offsets(point,nil,policy)
  return __raw_input.tapScreen(point.x+dx,point.y+dy,point.snapshotId)
end
function Input.tap(x,y) return __random_tap(x,y,false) end
function Input.tapScreen(x,y) return __random_tap(x,y,true) end
local function __random_down(x,y,raw)
  local state=__random_state(); local policy=state.policy or {x=0,y=0}
  local gesture={random=policy.x~=0 or policy.y~=0}
  if gesture.random then
    local p=__random_point(x,y,raw); local first,last=p,nil
    local bounds=state.bounds
    if bounds and not raw then first=__random_point(bounds[1],bounds[2],false); last=__random_point(bounds[3],bounds[4],false) end
    if p.snapshotId~=first.snapshotId then error('RANDOMIZATION_COORDINATES_CHANGED',0) end
    gesture.dx,gesture.dy=__random_offsets(first,last,policy); gesture.snapshot=p.snapshotId
    __raw_input.pointerDownScreen(p.x+gesture.dx,p.y+gesture.dy,p.snapshotId)
  else (raw and __raw_input.pointerDownScreen or __raw_input.pointerDown)(x,y) end
  state.gesture=gesture
end
function Input.pointerDown(x,y) return __random_down(x,y,false) end
function Input.pointerDownScreen(x,y) return __random_down(x,y,true) end
function Input.pointerMove(x,y)
  local state=__random_state(); local gesture=state.gesture
  if not gesture or not gesture.random then return __raw_input.pointerMove(x,y) end
  local p=__random_point(x,y,false)
  if p.snapshotId~=gesture.snapshot then error('RANDOMIZATION_COORDINATES_CHANGED',0) end
  local px,py=p.x+gesture.dx,p.y+gesture.dy
  if px<0 or py<0 or px>=p.width or py>=p.height then error('RANDOMIZATION_GESTURE_OUT_OF_BOUNDS',0) end
  return __raw_input.pointerMoveScreen(px,py,p.snapshotId)
end
function Input.pointerUp()
  __raw_input.pointerUp(); __random_state().gesture=nil
end
function Input.swipe(x1,y1,x2,y2,duration)
  local policy=__random_state().policy or {x=0,y=0}
  if policy.x==0 and policy.y==0 then return __raw_input.swipe(x1,y1,x2,y2,duration) end
  local p,q=__random_point(x1,y1,false),__random_point(x2,y2,false)
  local dx,dy=__random_offsets(p,q,policy)
  return __raw_input.swipeScreen(p.x+dx,p.y+dy,q.x+dx,q.y+dy,duration,p.snapshotId)
end
local function __random_sleep(milliseconds,exact)
  local state=__random_state(); local policy=state.policy
  if exact or state.gesture or milliseconds==0 or not policy or not policy.wait then return Task.sleep(milliseconds) end
  if milliseconds>86400000 then error('RANDOMIZATION_WAIT_TOO_LARGE',0) end
  local low,high
  if policy.unit=='percent' then
    low=math.floor(milliseconds*(100+policy.min)/100)
    high=math.floor(milliseconds*(100+policy.max)/100)
  else low=milliseconds+policy.min; high=milliseconds+policy.max end
  low,high=math.max(0,low),math.max(0,high)
  if high>86400000 then error('RANDOMIZATION_WAIT_TOO_LARGE',0) end
  return Task.sleep(__random_range(low,high))
end
";

#[cfg(test)]
mod tests {
    fn run(tail: &str) {
        let lua = mlua::Lua::new();
        lua.load("math.randomseed(42); moves={}; waits={}; Task={sleep=function(ms) waits[#waits+1]=ms end}; Input={resolvePoint=function(x,y,raw) if not raw then x=x*2;y=y*2 end; return {x=x,y=y,width=20,height=20,snapshotId=1} end,tapScreen=function(x,y) moves[#moves+1]={x,y} end,pointerDownScreen=function(x,y) moves[#moves+1]={x,y} end,pointerMoveScreen=function(x,y) moves[#moves+1]={x,y} end,pointerUp=function() end,swipeScreen=function(a,b,c,d) moves[#moves+1]={a,b,c,d} end}").exec().unwrap();
        lua.load(format!("{}\n{tail}", super::LUA)).exec().unwrap();
    }
    #[test]
    fn jitter_uses_physical_pixels_and_samples_only_legal_ranges() {
        run("__random_set(3,3,false,0,0,'milliseconds'); for i=1,100 do Input.tap(0,0);local p=moves[#moves];assert(p[1]>=0 and p[1]<=3 and p[2]>=0 and p[2]<=3) end; Input.tap(5,5); local p=moves[#moves];assert(p[1]>=7 and p[1]<=13 and p[2]>=7 and p[2]<=13)");
    }
    #[test]
    fn swipe_offset_is_shared_and_endpoint_intersection_is_respected() {
        run("__random_set(5,5,false,0,0,'milliseconds'); Input.swipe(0,0,9,9,100); local p=moves[1];assert(p[1]==p[3]-18 and p[2]==p[4]-18);assert(p[1]>=0 and p[1]<=1 and p[2]>=0 and p[2]<=1)");
    }
    #[test]
    fn pointer_offset_survives_policy_changes_and_waits_remain_exact() {
        run("__random_set(3,3,true,100,100,'milliseconds'); Input.pointerDown(5,5);local p=moves[1];__random_set(0,0,true,100,100,'milliseconds');Input.pointerMove(6,6);local q=moves[2];assert(q[1]-p[1]==2 and q[2]-p[2]==2);__random_sleep(10,false);Input.pointerUp();__random_sleep(10,false);assert(waits[1]==10 and waits[2]==110)");
    }
    #[test]
    fn percentage_zero_and_exact_waits_are_bounded() {
        run("__random_set(0,0,true,-10,10,'percent');__random_sleep(0,false);__random_sleep(1000,true);for i=1,100 do __random_sleep(1000,false);local ms=waits[#waits];assert(ms>=900 and ms<=1100) end;assert(waits[1]==0 and waits[2]==1000);local ok=pcall(function() __random_sleep(86400000,false) end);assert(not ok)");
    }
    #[test]
    fn coroutine_state_does_not_leak_between_tasks() {
        run("local a=coroutine.create(function() __random_set(1,1,true,100,100,'milliseconds'); Input.pointerDown(5,5);coroutine.yield();__random_sleep(10,false);Input.pointerUp() end);local b=coroutine.create(function() __random_set(0,0,true,20,20,'milliseconds');__random_sleep(10,false) end);assert(coroutine.resume(a));assert(coroutine.resume(b));assert(coroutine.resume(a));assert(waits[1]==30 and waits[2]==10)");
    }
    #[test]
    fn recognition_offset_does_not_mutate_result() {
        run("local found={x=5,y=6};__random_set(3,3,false,0,0,'milliseconds');Input.tapScreen(found.x,found.y);assert(found.x==5 and found.y==6)");
    }
    #[test]
    fn unknown_gesture_paths_fail_without_clamping_or_drawing_a_second_offset() {
        run("__random_set(3,3,false,0,0,'milliseconds');local old=math.random;math.random=function(a,b) return b end;Input.pointerDown(5,5);local ok,err=pcall(function() Input.pointerMove(9,9) end);math.random=old;assert(not ok and tostring(err):find('RANDOMIZATION_GESTURE_OUT_OF_BOUNDS'));assert(#moves==1)");
    }
    #[test]
    fn changed_mapping_is_rejected_before_pointer_movement() {
        run("__random_set(3,3,false,0,0,'milliseconds');Input.pointerDown(5,5);__raw_input.resolvePoint=function(x,y) return {x=x,y=y,width=20,height=20,snapshotId=2} end;local ok,err=pcall(function() Input.pointerMove(6,6) end);assert(not ok and tostring(err):find('RANDOMIZATION_COORDINATES_CHANGED'));assert(#moves==1)");
    }
    #[test]
    fn randomized_input_always_carries_the_resolved_snapshot_to_the_host() {
        run("__raw_input.tapScreen=function(x,y,s) assert(s==1) end; __raw_input.pointerDownScreen=function(x,y,s) assert(s==1) end; __raw_input.pointerMoveScreen=function(x,y,s) assert(s==1) end;__raw_input.swipeScreen=function(a,b,c,d,ms,s) assert(s==1) end;__random_set(1,1,false,0,0,'milliseconds');Input.tap(5,5);Input.pointerDown(5,5);Input.pointerMove(6,6);Input.pointerUp();Input.swipe(4,4,6,6,100)");
    }
}
