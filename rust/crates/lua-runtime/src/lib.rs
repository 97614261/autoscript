use mlua::prelude::{LuaChunkMode, LuaThreadStatus};
use mlua::{HookTriggers, Lua, LuaOptions, MultiValue, StdLib, Thread, Value, VmState};
use runtime_scheduler::{TaskFinalizer, TaskToken};
use std::cell::{Cell, RefCell};
use std::collections::BTreeMap;
use std::fmt;
use std::rc::Rc;
use std::thread::{self, ThreadId};

const MAX_CHUNK_BYTES: usize = 16 * 1024 * 1024;

struct ActiveTaskGuard<'a>(&'a Cell<Option<TaskToken>>);
impl Drop for ActiveTaskGuard<'_> {
    fn drop(&mut self) {
        self.0.set(None);
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LuaValidationError {
    SourceTooLarge { actual: usize, maximum: usize },
    InvalidChunkName,
    Syntax(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum LuaScalar {
    Nil,
    Boolean(bool),
    Integer(i64),
    Number(f64),
    Bytes(Vec<u8>),
}

#[derive(Debug, Clone)]
pub struct LuaDebugValue {
    pub scope: String,
    pub name: String,
    pub value: Option<LuaScalar>,
    pub declared_type: Option<String>,
    pub unsupported_type: Option<&'static str>,
    pub truncated: bool,
}

#[derive(Debug, Clone)]
pub struct LuaDebugSnapshot {
    pub flow_id: String,
    pub node_id: String,
    pub variables: Vec<LuaDebugValue>,
    pub variables_truncated: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum LuaInput {
    None,
    Boolean(bool),
    Integer(i64),
    Number(f64),
    Bytes(Vec<u8>),
    Values(Vec<LuaScalar>),
}

#[derive(Debug, Clone, PartialEq)]
pub enum LuaStep {
    BudgetExhausted,
    Yielded(Vec<LuaScalar>),
    Completed(Vec<LuaScalar>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LuaRuntimeError {
    WrongOwnerThread,
    InvalidInstructionBudget,
    InvalidMemoryLimit,
    TaskLimitReached { maximum: usize },
    TaskAlreadyRegistered(TaskToken),
    UnknownTask(TaskToken),
    Validation(LuaValidationError),
    UnsupportedValue(&'static str),
    Lua(String),
}

impl fmt::Display for LuaRuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::WrongOwnerThread => {
                formatter.write_str("Lua VM accessed outside its owner thread")
            }
            Self::InvalidInstructionBudget => {
                formatter.write_str("instruction budget must be greater than zero")
            }
            Self::InvalidMemoryLimit => {
                formatter.write_str("memory limit must be greater than zero")
            }
            Self::TaskLimitReached { maximum } => {
                write!(formatter, "Lua task limit reached; maximum is {maximum}")
            }
            Self::TaskAlreadyRegistered(task) => {
                write!(formatter, "Lua task {task:?} is already registered")
            }
            Self::UnknownTask(task) => write!(formatter, "Lua task {task:?} is not registered"),
            Self::Validation(error) => write!(formatter, "{error}"),
            Self::UnsupportedValue(kind) => write!(formatter, "unsupported Lua value: {kind}"),
            Self::Lua(message) => formatter.write_str(message),
        }
    }
}

impl From<LuaValidationError> for LuaRuntimeError {
    fn from(value: LuaValidationError) -> Self {
        Self::Validation(value)
    }
}

#[derive(Debug)]
struct LuaTask {
    thread: Thread,
    budget_yielded: Rc<Cell<bool>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LuaRuntimeConfig {
    pub instruction_budget: u32,
    pub memory_limit_bytes: usize,
    pub max_tasks: usize,
}

impl Default for LuaRuntimeConfig {
    fn default() -> Self {
        Self {
            instruction_budget: 50_000,
            memory_limit_bytes: 64 * 1024 * 1024,
            max_tasks: 256,
        }
    }
}

/// Owns one sandboxed PUC Lua 5.4 VM and all task coroutines created inside it.
///
/// `LuaTaskRegistry` intentionally has no `Send` implementation. The explicit owner check is a
/// second line of defense and produces a deterministic error if thread-affinity changes later.
#[derive(Debug)]
pub struct LuaTaskRegistry {
    lua: Lua,
    owner_thread: ThreadId,
    instruction_budget: u32,
    max_tasks: usize,
    tasks: BTreeMap<TaskToken, LuaTask>,
    callbacks: Rc<RefCell<BTreeMap<u64, (TaskToken, mlua::Function)>>>,
    active_task: Rc<Cell<Option<TaskToken>>>,
    next_callback: Rc<Cell<u64>>,
}

impl LuaTaskRegistry {
    /// Creates a VM without filesystem, process, package-loader or debug libraries.
    ///
    /// # Errors
    ///
    /// Returns an error for a zero instruction budget or Lua VM initialization failure.
    pub fn new(instruction_budget: u32) -> Result<Self, LuaRuntimeError> {
        Self::with_config(LuaRuntimeConfig {
            instruction_budget,
            ..LuaRuntimeConfig::default()
        })
    }

    /// Creates a VM with explicit CPU, memory and coroutine limits.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid limits or Lua VM initialization failure.
    pub fn with_config(config: LuaRuntimeConfig) -> Result<Self, LuaRuntimeError> {
        if config.instruction_budget == 0 {
            return Err(LuaRuntimeError::InvalidInstructionBudget);
        }
        if config.memory_limit_bytes == 0 {
            return Err(LuaRuntimeError::InvalidMemoryLimit);
        }
        let libraries =
            StdLib::COROUTINE | StdLib::TABLE | StdLib::STRING | StdLib::UTF8 | StdLib::MATH;
        let lua = Lua::new_with(libraries, LuaOptions::default())
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        lua.set_memory_limit(config.memory_limit_bytes)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        let globals = lua.globals();
        for name in ["dofile", "loadfile", "load"] {
            globals
                .set(name, Value::Nil)
                .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        }
        drop(globals);
        Ok(Self {
            lua,
            owner_thread: thread::current().id(),
            instruction_budget: config.instruction_budget,
            max_tasks: config.max_tasks,
            tasks: BTreeMap::new(),
            callbacks: Rc::new(RefCell::new(BTreeMap::new())),
            active_task: Rc::new(Cell::new(None)),
            next_callback: Rc::new(Cell::new(1)),
        })
    }

    #[must_use]
    pub fn task_count(&self) -> usize {
        self.tasks.len()
    }

    /// Compiles a text chunk in this VM and registers it as a resumable task coroutine.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid text, duplicate tasks, Lua failures or wrong-thread access.
    pub fn register_chunk(
        &mut self,
        task: TaskToken,
        source: &[u8],
        chunk_name: &str,
    ) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        validate_source_inputs(source, chunk_name)?;
        if self.tasks.contains_key(&task) {
            return Err(LuaRuntimeError::TaskAlreadyRegistered(task));
        }
        if self.tasks.len() >= self.max_tasks {
            return Err(LuaRuntimeError::TaskLimitReached {
                maximum: self.max_tasks,
            });
        }
        let function = self
            .lua
            .load(source)
            .set_name(chunk_name)
            .set_mode(LuaChunkMode::Text)
            .into_function()
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        self.register_function(task, function)
    }

    /// Loads a generated module chunk, evaluates only its declaration wrapper, and registers the
    /// returned entry function as the task coroutine.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid text, a non-function module result, duplicate tasks, Lua
    /// failures or wrong-thread access.
    pub fn register_entry_chunk(
        &mut self,
        task: TaskToken,
        source: &[u8],
        chunk_name: &str,
    ) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        validate_source_inputs(source, chunk_name)?;
        if self.tasks.contains_key(&task) {
            return Err(LuaRuntimeError::TaskAlreadyRegistered(task));
        }
        let module = self
            .lua
            .load(source)
            .set_name(chunk_name)
            .set_mode(LuaChunkMode::Text)
            .into_function()
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        let entry = module
            .call::<mlua::Function>(())
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        self.register_function(task, entry)
    }

    /// Installs the frozen API surface in the sandbox before user code is loaded.
    ///
    /// # Errors
    ///
    /// Returns an error on wrong-thread access or Lua initialization failure.
    #[allow(
        clippy::too_many_lines,
        reason = "embedded Lua API bootstrap is kept atomic"
    )]
    pub fn install_api(&self) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        self.lua
            .globals()
            .set("__autoscript_run_observation", true)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        let callbacks = Rc::clone(&self.callbacks);
        let active = Rc::clone(&self.active_task);
        let next = Rc::clone(&self.next_callback);
        let maximum = self.max_tasks;
        let register = self
            .lua
            .create_function(move |_, function: mlua::Function| {
                let owner = active.get().ok_or_else(|| {
                    mlua::Error::RuntimeError(
                        "callback registration requires a running task".into(),
                    )
                })?;
                let mut callbacks = callbacks.borrow_mut();
                if callbacks.len() >= maximum {
                    return Err(mlua::Error::RuntimeError("callback limit reached".into()));
                }
                let id = next.get();
                let following = id
                    .checked_add(1)
                    .filter(|id| i64::try_from(*id).is_ok())
                    .ok_or_else(|| mlua::Error::RuntimeError("callback IDs exhausted".into()))?;
                next.set(following);
                callbacks.insert(id, (owner, function));
                Ok(id)
            })
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        self.lua
            .globals()
            .set("__register_callback", register)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        self.lua
            .load(
                &br#"
                    local marker = "__AUTOSCRIPT_HOST_V1"
                    local registerCallback = __register_callback
                    __register_callback = nil
                    Task = {}
                    Timer = {}
                    System = {}
                    Math = {}
                    Input = {}
                    Screen = {}
                    Ocr = {}
                    Legacy = {}
                    Log = {}
                    Prompt = {}
                    UI = {}
                    Data = {}
                    Files = {}
                    Audio = {}
                    Clipboard = {}
                    Apps = {}
                    Floating = {}
                    Text = {}
                    Date = {}

                    local function integer(value, name, minimum, maximum)
                        if type(value) ~= "number" or value % 1 ~= 0 or value < minimum or value > maximum then
                            error(name .. " is outside the supported integer range", 3)
                        end
                    end

                    local function host(opcode, ...)
                        local values = table.pack(coroutine.yield(marker, opcode, ...))
                        if values[1] == false then
                            error((values[4] or "HOST_ERROR") .. ": " .. (values[5] or "failed"), 3)
                        end
                        return table.unpack(values, 2, values.n)
                    end

                    function Task.sleep(milliseconds)
                        if type(milliseconds) ~= "number" or milliseconds < 0 or milliseconds % 1 ~= 0 then
                            error("Task.sleep milliseconds must be a non-negative integer", 2)
                        end
                        local ok, code, message = coroutine.yield(marker, 3000, milliseconds)
                        if ok == false then error((code or "TASK_SLEEP") .. ": " .. (message or "failed"), 2) end
                    end

                    function Task.spawn(callback)
                        if type(callback) ~= "function" then error("Task.spawn requires a function", 2) end
                        return host(3001, registerCallback(callback))
                    end
                    function Task.cancel(task)
                        integer(task, "Task.cancel task", 1, 9223372036854775807)
                        return host(3002, task)
                    end
                    function Timer.every(milliseconds, callback)
                        integer(milliseconds, "Timer.every milliseconds", 10, 60000)
                        if type(callback) ~= "function" then error("Timer.every requires a function", 2) end
                        return host(3003, milliseconds, registerCallback(callback))
                    end
                    function Timer.cancel(timer)
                        integer(timer, "Timer.cancel timer", 1, 9223372036854775807)
                        return host(3004, timer)
                    end

                    local function writeLog(level, message)
                        local valueType = type(message)
                        if valueType ~= "string" and valueType ~= "number" and valueType ~= "boolean" and valueType ~= "nil" then
                            error("Log message must be a string, number, boolean, or nil", 3)
                        end
                        host(1100, level, tostring(message))
                    end

                    function Log.info(message) writeLog(1, message) end
                    function Log.warn(message) writeLog(2, message) end
                    function Log.error(message) writeLog(3, message) end
                    function Task.stop() return host(3010) end
                    function Task.restart() return host(3011) end
                    function Log.setEnabled(enabled)
                        if type(enabled)~='boolean' then error('enabled must be boolean',2) end
                        return host(1104,enabled)
                    end
                    function Prompt.setEnabled(enabled)
                        if type(enabled)~='boolean' then error('enabled must be boolean',2) end
                        return host(1105,enabled)
                    end

                    local function showPrompt(message, styleJson)
                        local valueType = type(message)
                        if valueType ~= "string" and valueType ~= "number" and valueType ~= "boolean" and valueType ~= "nil" then
                            error("Prompt message must be a string, number, boolean, or nil", 3)
                        end
                        if styleJson ~= nil and type(styleJson) ~= "string" then
                            error("Prompt style must be JSON text", 3)
                        end
                        if styleJson == nil then host(1101, tostring(message))
                        else host(1101, tostring(message), styleJson) end
                    end

                    function Prompt.show(message, styleJson) showPrompt(message, styleJson) end

                    function Prompt.toast(message, styleJson)
                        local valueType = type(message)
                        if valueType ~= "string" and valueType ~= "number" and valueType ~= "boolean" and valueType ~= "nil" then
                            error("Prompt message must be a string, number, boolean, or nil", 2)
                        end
                        if styleJson ~= nil and type(styleJson) ~= "string" then
                            error("Prompt style must be JSON text", 2)
                        end
                        host(1102, tostring(message), styleJson or "{}")
                    end

                    function UI.waitEvent() return host(1200) end
                    function UI.confirm(title,message,confirmText,cancelText,timeoutMs)
                        return host(1204,title,message,confirmText or 'OK',cancelText or 'Cancel',timeoutMs or 60000)
                    end
                    function UI.showDialogAndWait(pageId,blockOutside,timeoutMs)
                        if blockOutside == nil then blockOutside=true end
                        return host(1205,pageId,blockOutside,timeoutMs or 60000)
                    end
                    function UI.closeDialog() host(1206) end
                    function UI.getValue(id)
                        if type(id) ~= 'string' then error('UI requires a control ID',2) end
                        return host(1201,id)
                    end
                    function UI.setValue(id,value)
                        if type(id) ~= 'string' then error('UI requires a control ID',2) end
                        local kind=type(value)
                        if kind ~= 'string' and kind ~= 'boolean' and kind ~= 'number' then error('UI value must be scalar',2) end
                        host(1202,id,tostring(value))
                    end
                    function UI.command(id,operation,value)
                        if type(id) ~= 'string' or type(operation) ~= 'string' then error('invalid UI command',2) end
                        local kind=type(value)
                        if kind ~= 'string' and kind ~= 'boolean' and kind ~= 'number' then error('UI command value must be scalar',2) end
                        local result=host(1203,id,operation,tostring(value))
                        if string.sub(operation,1,4) == 'get:' then return result end
                    end

                    local function dataText(value, name)
                        if type(value) ~= 'string' or #value > 65536 then
                            error(name .. ' requires at most 65536 bytes of text', 3)
                        end
                        return value
                    end
                    function Data.length(json) return host(14000,dataText(json,'Data.length')) end
                    function Data.get(json,key) return host(14001,dataText(json,'Data.get'),dataText(key,'Data.get key')) end
                    function Data.set(json,key,valueJson) return host(14002,dataText(json,'Data.set'),dataText(key,'Data.set key'),dataText(valueJson,'Data.set value')) end
                    function Data.remove(json,key) return host(14003,dataText(json,'Data.remove'),dataText(key,'Data.remove key')) end
                    function Data.append(json,valueJson) return host(14004,dataText(json,'Data.append'),dataText(valueJson,'Data.append value')) end
                    function Data.keys(json) return host(14005,dataText(json,'Data.keys')) end
                    function Data.text(valueJson) return host(14006,dataText(valueJson,'Data.text')) end
                    function Text.length(...) return host(14200,...) end
                    function Text.contains(...) return host(14201,...) end
                    function Text.startsWith(...) return host(14202,...) end
                    function Text.endsWith(...) return host(14203,...) end
                    function Text.count(...) return host(14204,...) end
                    function Text.split(...) return host(14205,...) end
                    function Text.join(...) return host(14206,...) end
                    function Text.left(...) return host(14207,...) end
                    function Text.right(...) return host(14208,...) end
                    function Text.sub(...) return host(14209,...) end
                    function Text.insert(...) return host(14210,...) end
                    function Text.remove(...) return host(14211,...) end
                    function Text.replace(...) return host(14212,...) end
                    function Text.replaceFirst(...) return host(14213,...) end
                    function Text.repeatText(...) return host(14214,...) end
                    function Text.between(...) return host(14215,...) end
                    function Text.before(...) return host(14216,...) end
                    function Text.after(...) return host(14217,...) end
                    function Text.trim(...) return host(14218,...) end
                    function Text.find(...) return host(14219,...) end
                    function Text.lower(...) return host(14220,...) end
                    function Text.upper(...) return host(14221,...) end
                    function Text.regexMatch(...) return host(14222,...) end
                    function Text.regexExtract(...) return host(14223,...) end
                    function Text.regexAll(...) return host(14224,...) end
                    function Data.valid(...) return host(14007,...) end
                    function Data.type(...) return host(14008,...) end
                    function Data.has(...) return host(14009,...) end
                    function Data.getJson(...) return host(14010,...) end
                    function Date.format(...) return host(14300,...) end
                    function Date.addDays(...) return host(14301,...) end
                    function Date.daysBetween(...) return host(14302,...) end
                    function Files.read(path) return host(14100,dataText(path,'Files.read path')) end
                    function Files.write(path,text) host(14101,dataText(path,'Files.write path'),dataText(text,'Files.write text')) end
                    function Files.list(path) return host(14102,dataText(path,'Files.list path')) end
                    function Files.remove(path) host(14103,dataText(path,'Files.remove path')) end
                    function Files.append(...) return host(14110,...) end
                    function Files.exists(...) return host(14111,...) end
                    function Files.isDir(...) return host(14112,...) end
                    function Files.mkdir(...) return host(14113,...) end
                    function Files.copy(...) return host(14114,...) end
                    function Files.move(...) return host(14115,...) end
                    function Files.rmdir(...) return host(14116,...) end
                    function Files.readLine(...) return host(14117,...) end
                    function Files.insertLine(...) return host(14118,...) end
                    function Files.deleteLine(...) return host(14119,...) end
                    function Files.lineCount(...) return host(14120,...) end
                    function Files.writeLine(...) return host(14121,...) end
                    function Files.kvSet(...) return host(14122,...) end
                    function Files.kvGet(...) return host(14123,...) end
                    function Files.kvRemove(...) return host(14124,...) end
                    function Files.kvKeys(...) return host(14125,...) end
                    function Files.kvAdd(...) return host(14126,...) end
                    function Files.getSize(...) return host(14140,...) end
                    function Files.getExtension(...) return host(14141,...) end
                    function Files.directoryStats(...) return host(14142,...) end
                    function Files.toBase64(...) return host(14143,...) end
                    function Files.fromBase64(...) return host(14144,...) end
                    function Files.zip(...) return host(14145,...) end
                    function Files.unzip(...) return host(14146,...) end

                    function System.getScreenSize()
                        local ok, width, height, code, message = coroutine.yield(marker, 2000)
                        if not ok then error((code or "HOST_ERROR") .. ": " .. (message or "failed"), 2) end
                        return { width = width, height = height }
                    end

                    function System.elapsedRealtimeMillis()
                        local ok, milliseconds, code, message = coroutine.yield(marker, 2001)
                        if not ok then error((code or "HOST_ERROR") .. ": " .. (message or "failed"), 2) end
                        return milliseconds
                    end

                    function Math.distance(x1, y1, x2, y2)
                        return host(1000,x1,y1,x2,y2)
                    end
                    function Math.randInt(...) return host(1010,...) end
                    function Math.randStr(...) return host(1011,...) end
                    function Math.round(...) return host(1012,...) end
                    function Math.clamp(...) return host(1013,...) end
                    function Math.inRange(...) return host(1014,...) end
                    function Math.angle(...) return host(1015,...) end
                    function Math.flipAngle(...) return host(1016,...) end
                    function Math.joystickX(...) return host(1017,...) end
                    function Math.joystickY(...) return host(1018,...) end
                    function Math.red(...) return host(1019,...) end
                    function Math.green(...) return host(1020,...) end
                    function Math.blue(...) return host(1021,...) end
                    function System.getTimeMillis(...) return host(2002,...) end

                    function Input.tap(x, y)
                        integer(x, "Input.tap x", -2147483648, 2147483647)
                        integer(y, "Input.tap y", -2147483648, 2147483647)
                        host(4000, x, y)
                    end

                    function Input.swipe(x1, y1, x2, y2, durationMs)
                        integer(x1, "Input.swipe x1", -2147483648, 2147483647)
                        integer(y1, "Input.swipe y1", -2147483648, 2147483647)
                        integer(x2, "Input.swipe x2", -2147483648, 2147483647)
                        integer(y2, "Input.swipe y2", -2147483648, 2147483647)
                        integer(durationMs, "Input.swipe durationMs", 1, 5000)
                        host(4001, x1, y1, x2, y2, durationMs)
                    end

                    function Input.pointerDown(x, y)
                        integer(x, "Input.pointerDown x", -2147483648, 2147483647)
                        integer(y, "Input.pointerDown y", -2147483648, 2147483647)
                        host(4003, x, y)
                    end

                    function Input.pointerMove(x, y)
                        integer(x, "Input.pointerMove x", -2147483648, 2147483647)
                        integer(y, "Input.pointerMove y", -2147483648, 2147483647)
                        host(4004, x, y)
                    end

                    function Input.pointerUp()
                        host(4005)
                    end

                    local function screen_input(opcode,snapshotId,...)
                        if snapshotId == nil then return host(opcode,...) end
                        integer(snapshotId,'Input snapshotId',1,9223372036854775807)
                        local args={...}; args[#args+1]=snapshotId
                        return host(opcode,table.unpack(args))
                    end
                    function Input.tapScreen(x, y, snapshotId)
                        integer(x, "Input.tapScreen x", 0, 2147483647)
                        integer(y, "Input.tapScreen y", 0, 2147483647)
                        screen_input(4006,snapshotId,x,y)
                    end

                    function Input.pointerDownScreen(x, y, snapshotId)
                        integer(x, "Input.pointerDownScreen x", 0, 2147483647)
                        integer(y, "Input.pointerDownScreen y", 0, 2147483647)
                        screen_input(4007,snapshotId,x,y)
                    end

                    function Input.keyEvent(keyCode)
                        integer(keyCode, "Input.keyEvent keyCode", 0, 65535)
                        host(4002, keyCode)
                    end

                    function Input.record(durationMs,eventBudget)
                        return host(2200,durationMs,eventBudget)
                    end
                    function Input.playRecording(json)
                        local point=Input.resolvePoint(0,0,true)
                        local packed=host(2201,json)
                        local width,height,rotation,elapsed,count,offset=string.unpack('<i4i4i4i4i4',packed)
                        if width~=point.width or height~=point.height then error('RECORDING_SCREEN_CHANGED',2) end
                        local previous=0
                        for i=1,count do
                            local kind,start,duration,x1,y1,x2,y2,key
                            kind,start,duration,x1,y1,x2,y2,key,offset=string.unpack('<i4i4i4i4i4i4i4i4',packed,offset)
                            if start>previous then Task.sleep(start-previous) end
                            if kind==4 then Input.keyEvent(key)
                            else Input.swipeScreen(x1,y1,x2,y2,duration,point.snapshotId) end
                            previous=start+duration
                        end
                        if elapsed>previous then Task.sleep(elapsed-previous) end
                    end
                    function Audio.play(path) return host(15000,path) end
                    function Audio.stop() return host(15001) end
                    function Audio.getVolume() return host(15002) end
                    function Audio.setVolume(volume) return host(15003,volume) end
                    function Clipboard.getText() return host(16000) end
                    function Clipboard.setText(text) return host(16001,text) end
                    function Input.pasteText(text) host(2202,text); Input.keyEvent(279) end
                    function Apps.info(packageName) return host(16002,packageName) end
                    function Apps.open(packageName) return host(16003,packageName) end
                    function Floating.show(...) return host(17000,...) end
                    function Floating.hide(name) return host(17001,name) end
                    function Floating.close(name) return host(17002,name) end
                    function Floating.getBounds(name) return host(17003,name) end

                    function Input.resolvePoint(x,y,screenPixels)
                        integer(x,'Input.resolvePoint x',-2147483648,2147483647)
                        integer(y,'Input.resolvePoint y',-2147483648,2147483647)
                        if type(screenPixels) ~= 'boolean' then error('Input.resolvePoint screenPixels must be boolean',2) end
                        local ok,px,py,w,h,snapshot = coroutine.yield(marker,4008,x,y,screenPixels)
                        if not ok then error((w or 'INPUT_POINT_FAILED') .. ': ' .. (h or 'failed'),2) end
                        return {x=px,y=py,width=w,height=h,snapshotId=snapshot}
                    end
                    function Input.pointerMoveScreen(x,y,snapshotId)
                        integer(x,'Input.pointerMoveScreen x',0,2147483647)
                        integer(y,'Input.pointerMoveScreen y',0,2147483647)
                        screen_input(4009,snapshotId,x,y)
                    end
                    function Input.swipeScreen(x1,y1,x2,y2,durationMs,snapshotId)
                        integer(x1,'Input.swipeScreen x1',0,2147483647)
                        integer(y1,'Input.swipeScreen y1',0,2147483647)
                        integer(x2,'Input.swipeScreen x2',0,2147483647)
                        integer(y2,'Input.swipeScreen y2',0,2147483647)
                        integer(durationMs,'Input.swipeScreen durationMs',1,5000)
                        screen_input(4010,snapshotId,x1,y1,x2,y2,durationMs)
                    end

                    function Screen.capture()
                        return host(5000)
                    end

                    function Screen.cache(captureId)
                        integer(captureId, "Screen.cache captureId", 1, 9223372036854775807)
                        return host(5001, captureId)
                    end

                    function Screen.loadImage(path)
                        if type(path) ~= "string" or #path == 0 or #path > 256 then
                            error("Screen.loadImage path must be a non-empty string up to 256 bytes", 2)
                        end
                        return host(5003, path)
                    end

                    function Screen.release(frame)
                        integer(frame, "Screen.release frame", 1, 9223372036854775807)
                        host(5002, frame)
                    end

                    local function searchRegion(left, top, right, bottom, name)
                        integer(left, name .. " left", 0, 2147483647)
                        integer(top, name .. " top", 0, 2147483647)
                        integer(right, name .. " right", 1, 2147483647)
                        integer(bottom, name .. " bottom", 1, 2147483647)
                        if left >= right or top >= bottom then error(name .. " ROI is empty", 3) end
                    end

                    function Screen.findColor(frame, rgb, tolerance, left, top, right, bottom)
                        integer(frame, "Screen.findColor frame", 1, 9223372036854775807)
                        integer(rgb, "Screen.findColor rgb", 0, 16777215)
                        integer(tolerance, "Screen.findColor tolerance", 0, 255)
                        searchRegion(left, top, right, bottom, "Screen.findColor")
                        local x, y = host(5100, frame, rgb, tolerance, left, top, right, bottom)
                        if x == nil then return nil end
                        return { x = x, y = y }
                    end

                    function Screen.findImage(frame, template, tolerance, similarityPermille, left, top, right, bottom, direction)
                        integer(frame, "Screen.findImage frame", 1, 9223372036854775807)
                        integer(template, "Screen.findImage template", 1, 9223372036854775807)
                        integer(tolerance, "Screen.findImage tolerance", 0, 255)
                        integer(similarityPermille, "Screen.findImage similarityPermille", 0, 1000)
                        searchRegion(left, top, right, bottom, "Screen.findImage")
                        local x, y
                        if direction == nil then
                            x, y = host(5101, frame, template, tolerance, similarityPermille, left, top, right, bottom)
                        else
                            integer(direction, "Screen.findImage direction", 0, 4)
                            x, y = host(5101, frame, template, tolerance, similarityPermille, left, top, right, bottom, direction)
                        end
                        if x == nil then return nil end
                        return { x = x, y = y }
                    end

                    function Screen.getColor(frame, x, y)
                        integer(frame, "Screen.getColor frame", 1, 9223372036854775807)
                        integer(x, "Screen.getColor x", 0, 2147483647)
                        integer(y, "Screen.getColor y", 0, 2147483647)
                        return host(5102, frame, x, y)
                    end

                    function Screen.findImages(frame, pathsJson, tolerance, similarityPermille, left, top, right, bottom, direction)
                        integer(frame, "Screen.findImages frame", 1, 9223372036854775807)
                        if type(pathsJson) ~= "string" or #pathsJson > 16384 then error("Screen.findImages expects bounded JSON paths", 2) end
                        integer(tolerance, "Screen.findImages tolerance", 0, 255)
                        integer(similarityPermille, "Screen.findImages similarity", 0, 1000)
                        integer(direction or 0, "Screen.findImages direction", 0, 4)
                        searchRegion(left, top, right, bottom, "Screen.findImages")
                        local x,y,path,score,width,height = host(5107, frame, pathsJson, tolerance, similarityPermille, left, top, right, bottom, direction or 0)
                        if x == nil then return nil end
                        return {x=x,y=y,path=path,scorePermille=score,width=width,height=height}
                    end

                    function Screen.findAllImages(frame, pathsJson, tolerance, similarityPermille, left, top, right, bottom, direction, limit)
                        integer(frame, "Screen.findAllImages frame", 1, 9223372036854775807)
                        if type(pathsJson) ~= "string" or #pathsJson > 16384 then error("bounded JSON paths required", 2) end
                        integer(tolerance, "Screen.findAllImages tolerance", 0, 255)
                        integer(similarityPermille, "Screen.findAllImages similarity", 0, 1000)
                        integer(direction, "Screen.findAllImages direction", 0, 4)
                        integer(limit, "Screen.findAllImages limit", 1, 64)
                        searchRegion(left, top, right, bottom, "Screen.findAllImages")
                        return host(5114, frame, pathsJson, tolerance, similarityPermille, left, top, right, bottom, direction, limit)
                    end
                    function Screen.matchValue(result, index, field)
                        if type(result) ~= "string" or #result > 65536 then error("bounded match JSON required", 2) end
                        integer(index, "Screen.matchValue index", 0, 64)
                        if type(field) ~= "string" or #field > 32 then error("bounded match property required", 2) end
                        return host(5115, result, index, field)
                    end

                    function Screen.crop(frame, left, top, right, bottom)
                        integer(frame, "Screen.crop frame", 1, 9223372036854775807)
                        searchRegion(left, top, right, bottom, "Screen.crop")
                        return host(5005, frame, left, top, right, bottom)
                    end

                    function Screen.compareColor(frame, x, y, rgb, tolerance)
                        integer(frame, "Screen.compareColor frame", 1, 9223372036854775807)
                        integer(x, "Screen.compareColor x", 0, 2147483647)
                        integer(y, "Screen.compareColor y", 0, 2147483647)
                        integer(rgb, "Screen.compareColor rgb", 0, 16777215)
                        integer(tolerance, "Screen.compareColor tolerance", 0, 255)
                        return host(5103, frame, x, y, rgb, tolerance)
                    end

                    local function validateMultiColorSamples(samples)
                        if type(samples) ~= "table" then
                            error("Screen.findMultiColor samples must be a table", 3)
                        end
                        local count = #samples
                        if count > 64 then
                            error("Screen.findMultiColor supports at most 64 additional samples", 3)
                        end
                        local flattened = {}
                        for index = 1, count do
                            local sample = samples[index]
                            if type(sample) ~= "table" then
                                error("Screen.findMultiColor sample must be a table", 3)
                            end
                            for key in pairs(sample) do
                                if key ~= "x" and key ~= "y" and key ~= "rgb" and key ~= "tolerance" then
                                    error("Screen.findMultiColor sample has an unknown field", 3)
                                end
                            end
                            integer(sample.x, "Screen.findMultiColor sample.x", -2147483648, 2147483647)
                            integer(sample.y, "Screen.findMultiColor sample.y", -2147483648, 2147483647)
                            integer(sample.rgb, "Screen.findMultiColor sample.rgb", 0, 16777215)
                            integer(sample.tolerance, "Screen.findMultiColor sample.tolerance", 0, 255)
                            flattened[#flattened + 1] = sample.x
                            flattened[#flattened + 1] = sample.y
                            flattened[#flattened + 1] = sample.rgb
                            flattened[#flattened + 1] = sample.tolerance
                        end
                        return count, flattened
                    end

                    function Screen.findMultiColor(frame, anchorRgb, anchorTolerance, samples, left, top, right, bottom)
                        integer(frame, "Screen.findMultiColor frame", 1, 9223372036854775807)
                        integer(anchorRgb, "Screen.findMultiColor anchorRgb", 0, 16777215)
                        integer(anchorTolerance, "Screen.findMultiColor anchorTolerance", 0, 255)
                        searchRegion(left, top, right, bottom, "Screen.findMultiColor")
                        local count, flattened = validateMultiColorSamples(samples)
                        local arguments = { frame, anchorRgb, anchorTolerance, left, top, right, bottom, count }
                        for index = 1, #flattened do arguments[#arguments + 1] = flattened[index] end
                        local x, y = host(5104, table.unpack(arguments, 1, #arguments))
                        if x == nil then return nil end
                        return { x = x, y = y }
                    end

                    function Screen.countColor(frame, rgb, tolerance, left, top, right, bottom, limit)
                        integer(frame, "Screen.countColor frame", 1, 9223372036854775807)
                        integer(rgb, "Screen.countColor rgb", 0, 16777215)
                        integer(tolerance, "Screen.countColor tolerance", 0, 255)
                        searchRegion(left, top, right, bottom, "Screen.countColor")
                        integer(limit, "Screen.countColor limit", 1, 256)
                        return host(5105, frame, rgb, tolerance, left, top, right, bottom, limit)
                    end

                    function Screen.findAllColor(frame, rgb, tolerance, left, top, right, bottom, limit)
                        integer(frame, "Screen.findAllColor frame", 1, 9223372036854775807)
                        integer(rgb, "Screen.findAllColor rgb", 0, 16777215)
                        integer(tolerance, "Screen.findAllColor tolerance", 0, 255)
                        searchRegion(left, top, right, bottom, "Screen.findAllColor")
                        integer(limit, "Screen.findAllColor limit", 1, 256)
                        local raw = table.pack(host(5106, frame, rgb, tolerance, left, top, right, bottom, limit))
                        local count = raw[1]
                        local points = {}
                        for index = 1, count do
                            points[index] = { x = raw[index * 2], y = raw[index * 2 + 1] }
                        end
                        return points
                    end

                    local function legacyParameterString(value, name)
                        if type(value) ~= "string" or #value == 0 or #value > 32768 then
                            error(name .. " must be a non-empty string up to 32768 bytes", 3)
                        end
                    end

                    local function legacyXunTuResult(raw)
                        local count = raw[1]
                        local points = {}
                        for index = 1, count do
                            points[index] = { x = raw[index * 2], y = raw[index * 2 + 1] }
                        end
                        return {
                            count = count,
                            first = points[1],
                            points = points
                        }
                    end

                    function Legacy.duoDianZhaoSe(frame, left, top, width, height, parameters, direction, minimumMatchPercent)
                        integer(frame, "Legacy.duoDianZhaoSe frame", 1, 9223372036854775807)
                        integer(left, "Legacy.duoDianZhaoSe left", 0, 2147483647)
                        integer(top, "Legacy.duoDianZhaoSe top", 0, 2147483647)
                        integer(width, "Legacy.duoDianZhaoSe width", 1, 2147483647)
                        integer(height, "Legacy.duoDianZhaoSe height", 1, 2147483647)
                        legacyParameterString(parameters, "Legacy.duoDianZhaoSe parameters")
                        integer(direction, "Legacy.duoDianZhaoSe direction", 0, 4)
                        integer(minimumMatchPercent, "Legacy.duoDianZhaoSe minimumMatchPercent", 0, 100)
                        local raw = table.pack(host(5110, frame, left, top, width, height, parameters, direction, minimumMatchPercent))
                        return legacyXunTuResult(raw)
                    end

                    function Legacy.duoDianBiSe(frame, parameters, minimumMatchPercent)
                        integer(frame, "Legacy.duoDianBiSe frame", 1, 9223372036854775807)
                        legacyParameterString(parameters, "Legacy.duoDianBiSe parameters")
                        integer(minimumMatchPercent, "Legacy.duoDianBiSe minimumMatchPercent", 0, 100)
                        return host(5111, frame, parameters, minimumMatchPercent)
                    end

                    function Legacy.getRectColorNum(frame, left, top, width, height, parameters)
                        integer(frame, "Legacy.getRectColorNum frame", 1, 9223372036854775807)
                        integer(left, "Legacy.getRectColorNum left", 0, 2147483647)
                        integer(top, "Legacy.getRectColorNum top", 0, 2147483647)
                        integer(width, "Legacy.getRectColorNum width", 1, 2147483647)
                        integer(height, "Legacy.getRectColorNum height", 1, 2147483647)
                        legacyParameterString(parameters, "Legacy.getRectColorNum parameters")
                        return host(5112, frame, left, top, width, height, parameters)
                    end

                    function Legacy.getRgbColor(red, green, blue)
                        integer(red, "Legacy.getRgbColor red", 0, 255)
                        integer(green, "Legacy.getRgbColor green", 0, 255)
                        integer(blue, "Legacy.getRgbColor blue", 0, 255)
                        return host(5113, red, green, blue)
                    end

                    function Screen.captureSeries(maxFrames, durationMs, targetFps, allowPartial)
                        integer(maxFrames, "Screen.captureSeries maxFrames", 1, 120)
                        integer(durationMs, "Screen.captureSeries durationMs", 1, 60000)
                        integer(targetFps, "Screen.captureSeries targetFps", 1, 120)
                        if type(allowPartial) ~= "boolean" then
                            error("Screen.captureSeries allowPartial must be boolean", 2)
                        end

                        local frames = {}
                        local series = nil
                        local function cleanup()
                            if series ~= nil then
                                pcall(host, 5202, series)
                                series = nil
                            end
                            for index = #frames, 1, -1 do
                                pcall(Screen.release, frames[index].frame)
                            end
                        end

                        local ok, result = pcall(function()
                            local first = Screen.cache(Screen.capture())
                            frames[1] = { frame = first, timestampNanos = 0 }
                            local firstTimestamp, finished
                            series, firstTimestamp, finished = host(
                                5200,
                                first,
                                maxFrames,
                                durationMs,
                                targetFps,
                                allowPartial and 1 or 0
                            )
                            frames[1].timestampNanos = firstTimestamp
                            local droppedFrames = 0
                            local partial = false
                            local intervalMs = math.floor((1000 + targetFps - 1) / targetFps)
                            while not finished do
                                Task.sleep(intervalMs)
                                local status, captureId, timestampNanos, stepFinished, dropped, stepPartial =
                                    host(5004, series)
                                droppedFrames = dropped or droppedFrames
                                partial = stepPartial or partial
                                if status == 1 then
                                    local frame = host(5201, series, captureId)
                                    frames[#frames + 1] = {
                                        frame = frame,
                                        timestampNanos = timestampNanos
                                    }
                                elseif status ~= 0 and status ~= 2 then
                                    error("Screen.captureSeries received an invalid host status", 2)
                                end
                                finished = stepFinished
                            end
                            host(5202, series)
                            series = nil
                            return {
                                frames = frames,
                                droppedFrames = droppedFrames,
                                partial = partial
                            }
                        end)
                        if not ok then
                            cleanup()
                            error(result, 2)
                        end
                        return result
                    end

                    function Ocr.loadDictionary(path)
                        -- Existing glyph dictionary path stays unchanged.
                        if type(path) ~= "string" or #path == 0 or #path > 256 then
                            error("Ocr.loadDictionary path must be a non-empty string up to 256 bytes", 2)
                        end
                        return host(6000, path)
                    end

                    function Ocr.releaseDictionary(dictionary)
                        integer(dictionary, "Ocr.releaseDictionary dictionary", 1, 9223372036854775807)
                        host(6001, dictionary)
                    end

                    function Ocr.glyph(frame, dictionary, foregroundRgb, tolerance, similarityPermille, left, top, right, bottom, spaceGapColumns)
                        integer(frame, "Ocr.glyph frame", 1, 9223372036854775807)
                        integer(dictionary, "Ocr.glyph dictionary", 1, 9223372036854775807)
                        integer(foregroundRgb, "Ocr.glyph foregroundRgb", 0, 16777215)
                        integer(tolerance, "Ocr.glyph tolerance", 0, 255)
                        integer(similarityPermille, "Ocr.glyph similarityPermille", 0, 1000)
                        searchRegion(left, top, right, bottom, "Ocr.glyph")
                        integer(spaceGapColumns, "Ocr.glyph spaceGapColumns", 0, 65535)
                        local text, coverage, average = host(6100, frame, dictionary, foregroundRgb, tolerance, similarityPermille, left, top, right, bottom, spaceGapColumns)
                        return {
                            text = text,
                            coveragePermille = coverage,
                            averageScorePermille = average
                        }
                    end

                    function Ocr.glyphDetails(frame, dictionary, foregroundRgb, tolerance, similarityPermille, left, top, right, bottom, spaceGapColumns)
                        return host(6102, frame, dictionary, foregroundRgb, tolerance, similarityPermille, left, top, right, bottom, spaceGapColumns)
                    end
                    function Ocr.findText(details, text) return host(6103, details, text) end
                    function Screen.recognitionValue(result, shape, index, field) return host(5122, result, shape, index, field) end
                    function Screen.transform(frame, options) return host(5010, frame, options) end
                    function Screen.findAllMultiColor(frame, options) return host(5120, frame, options) end
                    function Screen.components(frame, options) return host(5121, frame, options) end
                    function Screen.savePng(frame, path) return host(14130, frame, path) end

                    function Screen.findGray(frame, template, similarity, left, top, right, bottom)
                        integer(frame, "Screen.findGray frame", 1, 9223372036854775807)
                        integer(template, "Screen.findGray template", 1, 9223372036854775807)
                        integer(similarity, "Screen.findGray similarity", 0, 1000)
                        searchRegion(left, top, right, bottom, "Screen.findGray")
                        local x,y,score=host(5108,frame,template,similarity,left,top,right,bottom)
                        if x==nil then return nil end
                        return {x=x,y=y,scorePermille=score}
                    end

                    function Ocr.alphanumeric(frame, left, top, right, bottom, confidence)
                        confidence=confidence or 500
                        integer(frame, "Ocr.alphanumeric frame", 1, 9223372036854775807)
                        integer(confidence, "Ocr.alphanumeric confidence", 0, 1000)
                        searchRegion(left, top, right, bottom, "Ocr.alphanumeric")
                        local text,score=host(6101,frame,left,top,right,bottom,confidence)
                        return {text=text,averageScorePermille=score}
                    end

                    Sleep = Task.sleep
                    Tap = Input.tap
                    Swipe = Input.swipe
                    CaptureScreen = Screen.capture
                    LoadImage = Screen.loadImage
                    FindColor = Screen.findColor
                    FindImage = Screen.findImage
                    GetColor = Screen.getColor
                    CompareColor = Screen.compareColor
                    FindMultiColor = Screen.findMultiColor
                    CountColor = Screen.countColor
                    FindAllColor = Screen.findAllColor
                    CaptureSeries = Screen.captureSeries
                    LoadDictionary = Ocr.loadDictionary
                    ReleaseDictionary = Ocr.releaseDictionary
                    GlyphOcr = Ocr.glyph
                    Print = Log.info
                "#[..],
            )
            .set_name("runtime-api")
            .set_mode(LuaChunkMode::Text)
            .exec()
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))
    }

    /// Starts a VM-local callback without moving Lua functions across the host boundary.
    /// # Errors
    /// Rejects foreign callbacks, stale IDs, task limits and coroutine initialization failures.
    pub fn register_callback_task(
        &mut self,
        task: TaskToken,
        owner: TaskToken,
        callback: u64,
        retain: bool,
    ) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        let function = self
            .callbacks
            .borrow()
            .get(&callback)
            .filter(|(registered, _)| *registered == owner)
            .map(|(_, function)| function.clone())
            .ok_or_else(|| LuaRuntimeError::Lua("unknown or foreign callback".into()))?;
        self.register_function(task, function)?;
        if !retain {
            self.callbacks.borrow_mut().remove(&callback);
        }
        Ok(())
    }

    pub fn remove_callback(&self, owner: TaskToken, callback: u64) {
        let mut callbacks = self.callbacks.borrow_mut();
        if callbacks
            .get(&callback)
            .is_some_and(|(registered, _)| *registered == owner)
        {
            callbacks.remove(&callback);
        }
    }

    #[must_use]
    pub fn owns_callback(&self, owner: TaskToken, callback: u64) -> bool {
        self.callbacks
            .borrow()
            .get(&callback)
            .is_some_and(|(registered, _)| *registered == owner)
    }

    fn register_function(
        &mut self,
        task: TaskToken,
        function: mlua::Function,
    ) -> Result<(), LuaRuntimeError> {
        if self.tasks.contains_key(&task) {
            return Err(LuaRuntimeError::TaskAlreadyRegistered(task));
        }
        if self.tasks.len() >= self.max_tasks {
            return Err(LuaRuntimeError::TaskLimitReached {
                maximum: self.max_tasks,
            });
        }
        let coroutine = self
            .lua
            .create_thread(function)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        let budget_yielded = Rc::new(Cell::new(false));
        let hook_flag = Rc::clone(&budget_yielded);
        coroutine
            .set_hook(
                HookTriggers::new().every_nth_instruction(self.instruction_budget),
                move |_, _| {
                    hook_flag.set(true);
                    Ok(VmState::Yield)
                },
            )
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        self.tasks.insert(
            task,
            LuaTask {
                thread: coroutine,
                budget_yielded,
            },
        );
        Ok(())
    }

    /// Resumes one coroutine and converts its yielded/returned values to owned scalar data.
    /// No `mlua::Value` can leave the VM owner.
    ///
    /// # Errors
    ///
    /// Returns an error for missing tasks, Lua errors, unsupported values or wrong-thread access.
    pub fn resume(&mut self, task: TaskToken, input: LuaInput) -> Result<LuaStep, LuaRuntimeError> {
        self.ensure_owner_thread()?;
        let record = self
            .tasks
            .get(&task)
            .ok_or(LuaRuntimeError::UnknownTask(task))?;
        record.budget_yielded.set(false);
        self.active_task.set(Some(task));
        let _active_guard = ActiveTaskGuard(&self.active_task);
        let values = match input {
            LuaInput::None => record.thread.resume::<MultiValue>(()),
            LuaInput::Boolean(value) => record.thread.resume::<MultiValue>(value),
            LuaInput::Integer(value) => record.thread.resume::<MultiValue>(value),
            LuaInput::Number(value) => record.thread.resume::<MultiValue>(value),
            LuaInput::Bytes(value) => {
                let value = self
                    .lua
                    .create_string(value)
                    .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
                record.thread.resume::<MultiValue>(value)
            }
            LuaInput::Values(values) => {
                let values = values
                    .into_iter()
                    .map(|value| lua_value(&self.lua, value))
                    .collect::<Result<Vec<_>, _>>()?;
                record
                    .thread
                    .resume::<MultiValue>(MultiValue::from_vec(values))
            }
        };
        self.active_task.set(None);
        let values = values.map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        if record.budget_yielded.get() {
            return Ok(LuaStep::BudgetExhausted);
        }
        let values = values
            .into_iter()
            .map(lua_scalar)
            .collect::<Result<Vec<_>, _>>()?;
        match record.thread.status() {
            LuaThreadStatus::Resumable => Ok(LuaStep::Yielded(values)),
            LuaThreadStatus::Finished => Ok(LuaStep::Completed(values)),
            status => Err(LuaRuntimeError::Lua(format!(
                "unexpected coroutine status after resume: {status:?}"
            ))),
        }
    }

    /// Returns one owned scalar global for diagnostics and contract tests.
    ///
    /// # Errors
    ///
    /// Returns an error for unsupported values, Lua failures or wrong-thread access.
    pub fn global_scalar(&self, name: &str) -> Result<LuaScalar, LuaRuntimeError> {
        self.ensure_owner_thread()?;
        let value = self
            .lua
            .globals()
            .get(name)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        lua_scalar(value)
    }

    /// Owner-thread-only debugger switch; never evaluates caller-provided code.
    ///
    /// # Errors
    /// Returns an error on wrong-thread access or when the VM rejects the assignment.
    pub fn set_debug_step(&self, enabled: bool) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        self.lua
            .globals()
            .raw_set("__autoscript_debug_step", enabled)
            .map_err(|e| LuaRuntimeError::Lua(e.to_string()))
    }

    /// Bounded passive snapshot of the most recent compiled node boundary.
    #[must_use]
    pub fn debug_snapshot(&self) -> Option<LuaDebugSnapshot> {
        self.ensure_owner_thread().ok()?;
        let snapshot: mlua::Table = self
            .lua
            .globals()
            .raw_get("__autoscript_debug_snapshot")
            .ok()?;
        let read_id = |key| -> Option<String> {
            let value: mlua::LuaString = snapshot.raw_get(key).ok()?;
            let bytes = value.as_bytes();
            if bytes.is_empty() || bytes.len() > 128 {
                return None;
            }
            std::str::from_utf8(&bytes).ok().map(str::to_owned)
        };
        let mut result = LuaDebugSnapshot {
            flow_id: read_id("flowId")?,
            node_id: read_id("nodeId")?,
            variables: Vec::new(),
            variables_truncated: false,
        };
        for (scope, type_key, value_key) in [
            ("local", "localTypes", "locals"),
            ("global", "globalTypes", "globals"),
        ] {
            let types: mlua::Table = snapshot.raw_get(type_key).ok()?;
            let values: mlua::Table = snapshot.raw_get(value_key).ok()?;
            let overrides = if scope == "local" {
                snapshot
                    .raw_get::<Option<mlua::Table>>("localOverrides")
                    .ok()
                    .flatten()
            } else {
                None
            };
            let (names, truncated) =
                Self::debug_variable_names(&types, &values, overrides.as_ref())?;
            result.variables_truncated |= truncated;
            for name in names {
                if result.variables.len() >= 128 {
                    result.variables_truncated = true;
                    break;
                }
                let mut value: Value = values.raw_get(name.as_str()).ok()?;
                if let Some(overrides) = &overrides {
                    let override_value: Value = overrides.raw_get(name.as_str()).ok()?;
                    if !matches!(override_value, Value::Nil) {
                        value = override_value;
                    }
                }
                let declared_type = types
                    .raw_get::<Value>(name.as_str())
                    .ok()
                    .and_then(|value| {
                        let Value::String(kind) = value else {
                            return None;
                        };
                        let bytes = kind.as_bytes();
                        let kind = std::str::from_utf8(&bytes).ok()?;
                        matches!(kind, "boolean" | "integer" | "number" | "string" | "image")
                            .then(|| kind.to_owned())
                    });
                let (scalar, truncated, unsupported_type) = match value {
                    Value::String(bytes) => (
                        Some(LuaScalar::Bytes(
                            bytes.as_bytes()[..bytes.as_bytes().len().min(1024)].to_vec(),
                        )),
                        bytes.as_bytes().len() > 1024
                            || std::str::from_utf8(&bytes.as_bytes()).is_err(),
                        None,
                    ),
                    Value::Number(value) if !value.is_finite() => {
                        (None, false, Some("non-finite number"))
                    }
                    other => match lua_scalar(other) {
                        Ok(value) => (Some(value), false, None),
                        Err(LuaRuntimeError::UnsupportedValue(kind)) => (None, false, Some(kind)),
                        Err(_) => return None,
                    },
                };
                result.variables.push(LuaDebugValue {
                    scope: scope.into(),
                    name,
                    value: scalar,
                    declared_type,
                    unsupported_type,
                    truncated,
                });
            }
        }
        Some(result)
    }

    fn debug_variable_names(
        types: &mlua::Table,
        values: &mlua::Table,
        overrides: Option<&mlua::Table>,
    ) -> Option<(std::collections::BTreeSet<String>, bool)> {
        let mut names = std::collections::BTreeSet::new();
        let mut truncated = false;
        for table in [types, values].into_iter().chain(overrides) {
            for (index, entry) in table.clone().pairs::<Value, Value>().take(129).enumerate() {
                if index == 128 {
                    truncated = true;
                    break;
                }
                let (Value::String(name), _) = entry.ok()? else {
                    truncated = true;
                    continue;
                };
                let bytes = name.as_bytes();
                if bytes.is_empty() || bytes.len() > 128 {
                    truncated = true;
                    continue;
                }
                if let Ok(name) = std::str::from_utf8(&bytes) {
                    names.insert(name.to_owned());
                } else {
                    truncated = true;
                }
            }
        }
        Some((names, truncated))
    }

    fn ensure_owner_thread(&self) -> Result<(), LuaRuntimeError> {
        if thread::current().id() == self.owner_thread {
            Ok(())
        } else {
            Err(LuaRuntimeError::WrongOwnerThread)
        }
    }

    fn close_registered_task(&mut self, task: TaskToken) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
        self.callbacks
            .borrow_mut()
            .retain(|_, (owner, _)| *owner != task);
        let record = self
            .tasks
            .remove(&task)
            .ok_or(LuaRuntimeError::UnknownTask(task))?;
        let replacement = self
            .lua
            .create_function(|_, ()| Ok(()))
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
        record
            .thread
            .reset(replacement)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string()))
    }
}

fn lua_value(lua: &Lua, value: LuaScalar) -> Result<Value, LuaRuntimeError> {
    match value {
        LuaScalar::Nil => Ok(Value::Nil),
        LuaScalar::Boolean(value) => Ok(Value::Boolean(value)),
        LuaScalar::Integer(value) => Ok(Value::Integer(value)),
        LuaScalar::Number(value) => Ok(Value::Number(value)),
        LuaScalar::Bytes(value) => lua
            .create_string(value)
            .map(Value::String)
            .map_err(|error| LuaRuntimeError::Lua(error.to_string())),
    }
}

impl TaskFinalizer for LuaTaskRegistry {
    fn close_task(&mut self, task: TaskToken) -> Result<(), String> {
        match self.close_registered_task(task) {
            Ok(()) | Err(LuaRuntimeError::UnknownTask(_)) => Ok(()),
            Err(error) => Err(error.to_string()),
        }
    }
}

fn lua_scalar(value: Value) -> Result<LuaScalar, LuaRuntimeError> {
    match value {
        Value::Nil => Ok(LuaScalar::Nil),
        Value::Boolean(value) => Ok(LuaScalar::Boolean(value)),
        Value::Integer(value) => Ok(LuaScalar::Integer(value)),
        Value::Number(value) => Ok(LuaScalar::Number(value)),
        Value::String(value) => Ok(LuaScalar::Bytes(value.as_bytes().to_vec())),
        Value::LightUserData(_) => Err(LuaRuntimeError::UnsupportedValue("light userdata")),
        Value::Table(_) => Err(LuaRuntimeError::UnsupportedValue("table")),
        Value::Function(_) => Err(LuaRuntimeError::UnsupportedValue("function")),
        Value::Thread(_) => Err(LuaRuntimeError::UnsupportedValue("thread")),
        Value::UserData(_) => Err(LuaRuntimeError::UnsupportedValue("userdata")),
        Value::Error(_) => Err(LuaRuntimeError::UnsupportedValue("error")),
        Value::Other(_) => Err(LuaRuntimeError::UnsupportedValue("other")),
    }
}

impl fmt::Display for LuaValidationError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SourceTooLarge { actual, maximum } => {
                write!(
                    formatter,
                    "Lua source is {actual} bytes; maximum is {maximum}"
                )
            }
            Self::InvalidChunkName => formatter.write_str("Lua chunk name is invalid"),
            Self::Syntax(message) => formatter.write_str(message),
        }
    }
}

/// Compiles a text chunk with the vendored PUC Lua 5.4 parser without executing it.
///
/// # Errors
///
/// Returns an error for oversized source, unsafe diagnostic names, or Lua 5.4 syntax errors.
pub fn validate_text_chunk(source: &[u8], chunk_name: &str) -> Result<(), LuaValidationError> {
    validate_source_inputs(source, chunk_name)?;
    let lua = Lua::new();
    lua.load(source)
        .set_name(chunk_name)
        .set_mode(LuaChunkMode::Text)
        .into_function()
        .map(|_| ())
        .map_err(|error| LuaValidationError::Syntax(error.to_string()))
}

fn validate_source_inputs(source: &[u8], chunk_name: &str) -> Result<(), LuaValidationError> {
    if source.len() > MAX_CHUNK_BYTES {
        return Err(LuaValidationError::SourceTooLarge {
            actual: source.len(),
            maximum: MAX_CHUNK_BYTES,
        });
    }
    if chunk_name.is_empty()
        || chunk_name.len() > 128
        || chunk_name
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte == b'@' || byte == b'=')
    {
        return Err(LuaValidationError::InvalidChunkName);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use runtime_scheduler::{
        ManualClock, MonoTime, ResourceId, ResourceKind, ResourceOwner, Scheduler, SchedulerConfig,
        SchedulerEvent, TaskFinalizer, TaskGeneration, TaskId, TaskToken,
    };

    use super::{
        validate_text_chunk, LuaInput, LuaRuntimeConfig, LuaRuntimeError, LuaScalar, LuaStep,
        LuaTaskRegistry, LuaValidationError,
    };

    fn task(id: u64) -> TaskToken {
        TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        }
    }

    fn resource(local_id: u64) -> ResourceId {
        ResourceId::try_new(ResourceKind::Generic, local_id).expect("test resource id")
    }

    #[test]
    fn valid_lua_54_chunk_is_compiled_without_running() {
        validate_text_chunk(
            b"local closed <close> = nil\nreturn function() end\n",
            "valid",
        )
        .expect("Lua 5.4 syntax");
    }

    #[test]
    fn invalid_chunk_is_rejected() {
        let error = validate_text_chunk(b"local = broken", "invalid")
            .expect_err("invalid syntax must fail");
        assert!(matches!(error, LuaValidationError::Syntax(_)));
    }

    #[test]
    fn chunk_is_not_executed_during_validation() {
        validate_text_chunk(b"error('must not run')", "compile-only")
            .expect("validation only compiles");
    }

    #[test]
    fn coroutine_yields_and_resumes_inside_one_vm() {
        let mut runtime = LuaTaskRegistry::new(10_000).expect("runtime");
        runtime
            .register_chunk(
                task(1),
                b"local value = coroutine.yield('host')\nreturn value, 42",
                "yield-resume",
            )
            .expect("register");
        assert_eq!(
            runtime.resume(task(1), LuaInput::None).expect("yield"),
            LuaStep::Yielded(vec![LuaScalar::Bytes(b"host".to_vec())])
        );
        assert_eq!(
            runtime
                .resume(task(1), LuaInput::Bytes(b"done".to_vec()))
                .expect("complete"),
            LuaStep::Completed(vec![
                LuaScalar::Bytes(b"done".to_vec()),
                LuaScalar::Integer(42),
            ])
        );
    }

    #[test]
    fn screen_input_optional_snapshot_is_validated_and_transmitted() {
        for (source, opcode, args) in [
            ("Input.tapScreen(2,3)", 4006, vec![2, 3]),
            ("Input.tapScreen(2,3,9)", 4006, vec![2, 3, 9]),
            ("Input.pointerDownScreen(2,3,9)", 4007, vec![2, 3, 9]),
            ("Input.pointerMoveScreen(2,3,9)", 4009, vec![2, 3, 9]),
            (
                "Input.swipeScreen(2,3,4,5,100,9)",
                4010,
                vec![2, 3, 4, 5, 100, 9],
            ),
        ] {
            let mut runtime = LuaTaskRegistry::new(10_000).unwrap();
            runtime.install_api().unwrap();
            runtime
                .register_chunk(task(1), source.as_bytes(), "input-snapshot")
                .unwrap();
            let LuaStep::Yielded(values) = runtime.resume(task(1), LuaInput::None).unwrap() else {
                panic!("host request required");
            };
            assert_eq!(values[1], LuaScalar::Integer(opcode));
            assert_eq!(
                values[2..],
                args.into_iter().map(LuaScalar::Integer).collect::<Vec<_>>()
            );
        }
        for source in [
            "Input.tapScreen(2,3,0)",
            "Input.pointerDownScreen(2,3,-1)",
            "Input.pointerMoveScreen(2,3,1.5)",
            "Input.swipeScreen(2,3,4,5,100,'9')",
        ] {
            let mut runtime = LuaTaskRegistry::new(10_000).unwrap();
            runtime.install_api().unwrap();
            runtime
                .register_chunk(task(1), source.as_bytes(), "bad-snapshot")
                .unwrap();
            assert!(runtime.resume(task(1), LuaInput::None).is_err());
        }
    }

    #[test]
    fn instruction_hook_preempts_cpu_bound_coroutine() {
        let mut runtime = LuaTaskRegistry::new(500).expect("runtime");
        runtime
            .register_chunk(task(2), b"local n=0 while true do n=n+1 end", "budget")
            .expect("register");
        assert_eq!(
            runtime.resume(task(2), LuaInput::None).expect("slice one"),
            LuaStep::BudgetExhausted
        );
        assert_eq!(
            runtime.resume(task(2), LuaInput::None).expect("slice two"),
            LuaStep::BudgetExhausted
        );
    }

    #[test]
    fn closing_yielded_thread_runs_lua_54_close_metamethod() {
        let mut runtime = LuaTaskRegistry::new(10_000).expect("runtime");
        runtime
            .register_chunk(
                task(3),
                br"
                    closed = 0
                    local guard <close> = setmetatable({}, {
                        __close = function() closed = closed + 1 end
                    })
                    coroutine.yield('waiting')
                ",
                "close-thread",
            )
            .expect("register");
        assert!(matches!(
            runtime.resume(task(3), LuaInput::None).expect("yield"),
            LuaStep::Yielded(_)
        ));
        runtime.close_task(task(3)).expect("close coroutine");
        assert_eq!(
            runtime.global_scalar("closed").expect("closed global"),
            LuaScalar::Integer(1)
        );
        assert_eq!(runtime.task_count(), 0);
    }

    #[test]
    fn dangerous_standard_libraries_are_absent() {
        let mut runtime = LuaTaskRegistry::new(10_000).expect("runtime");
        runtime
            .register_chunk(
                task(4),
                b"return io == nil, os == nil, package == nil, debug == nil, dofile == nil, loadfile == nil, load == nil",
                "sandbox",
            )
            .expect("register");
        assert_eq!(
            runtime.resume(task(4), LuaInput::None).expect("run"),
            LuaStep::Completed(vec![LuaScalar::Boolean(true); 7])
        );
    }

    #[test]
    fn vm_memory_limit_fails_closed() {
        let mut runtime = LuaTaskRegistry::with_config(LuaRuntimeConfig {
            instruction_budget: 10_000,
            memory_limit_bytes: 512 * 1024,
            max_tasks: 1,
        })
        .expect("runtime");
        runtime
            .register_chunk(
                task(5),
                b"return string.rep('x', 2 * 1024 * 1024)",
                "memory",
            )
            .expect("register");
        assert!(matches!(
            runtime.resume(task(5), LuaInput::None),
            Err(LuaRuntimeError::Lua(_))
        ));
    }

    #[test]
    fn scheduler_cancel_closes_lua_before_releasing_task_resources() {
        let runtime = LuaTaskRegistry::new(10_000).expect("runtime");
        let mut scheduler = Scheduler::with_finalizer(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            runtime,
        );
        let task = scheduler.spawn(None, false).expect("task");
        scheduler
            .finalizer_mut()
            .register_chunk(
                task,
                br"
                    closed_by_scheduler = 0
                    local guard <close> = setmetatable({}, {
                        __close = function() closed_by_scheduler = closed_by_scheduler + 1 end
                    })
                    coroutine.yield('waiting')
                ",
                "scheduler-close",
            )
            .expect("register");
        assert!(matches!(
            scheduler
                .finalizer_mut()
                .resume(task, LuaInput::None)
                .expect("yield"),
            LuaStep::Yielded(_)
        ));
        scheduler
            .resources_mut()
            .acquire_new(ResourceOwner::Task(task), resource(81))
            .expect("resource");
        scheduler
            .handle()
            .request_cancel(task)
            .expect("cancel queued");

        let events = scheduler.process();
        assert_eq!(
            scheduler
                .finalizer()
                .global_scalar("closed_by_scheduler")
                .expect("close marker"),
            LuaScalar::Integer(1)
        );
        assert!(events.contains(&SchedulerEvent::ResourceReleased(resource(81))));
        assert!(!events
            .iter()
            .any(|event| matches!(event, SchedulerEvent::FinalizerFailed { .. })));
    }
}
