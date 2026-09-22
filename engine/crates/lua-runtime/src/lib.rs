use mlua::prelude::{LuaChunkMode, LuaThreadStatus};
use mlua::{HookTriggers, Lua, LuaOptions, MultiValue, StdLib, Thread, Value, VmState};
use runtime_scheduler::{TaskFinalizer, TaskToken};
use std::cell::Cell;
use std::collections::BTreeMap;
use std::fmt;
use std::rc::Rc;
use std::thread::{self, ThreadId};

const MAX_CHUNK_BYTES: usize = 16 * 1024 * 1024;

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
            .load(
                &br#"
                    local marker = "__AUTOSCRIPT_HOST_V1"
                    Task = {}
                    System = {}
                    Math = {}
                    Input = {}
                    Screen = {}
                    Ocr = {}
                    Legacy = {}
                    Log = {}

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
                        local dx, dy = x2 - x1, y2 - y1
                        return math.sqrt(dx * dx + dy * dy)
                    end

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

                    function Input.keyEvent(keyCode)
                        integer(keyCode, "Input.keyEvent keyCode", 0, 65535)
                        host(4002, keyCode)
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

                    function Screen.findImage(frame, template, tolerance, similarityPermille, left, top, right, bottom)
                        integer(frame, "Screen.findImage frame", 1, 9223372036854775807)
                        integer(template, "Screen.findImage template", 1, 9223372036854775807)
                        integer(tolerance, "Screen.findImage tolerance", 0, 255)
                        integer(similarityPermille, "Screen.findImage similarityPermille", 0, 1000)
                        searchRegion(left, top, right, bottom, "Screen.findImage")
                        local x, y = host(5101, frame, template, tolerance, similarityPermille, left, top, right, bottom)
                        if x == nil then return nil end
                        return { x = x, y = y }
                    end

                    function Screen.getColor(frame, x, y)
                        integer(frame, "Screen.getColor frame", 1, 9223372036854775807)
                        integer(x, "Screen.getColor x", 0, 2147483647)
                        integer(y, "Screen.getColor y", 0, 2147483647)
                        return host(5102, frame, x, y)
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
        }
        .map_err(|error| LuaRuntimeError::Lua(error.to_string()))?;
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

    fn ensure_owner_thread(&self) -> Result<(), LuaRuntimeError> {
        if thread::current().id() == self.owner_thread {
            Ok(())
        } else {
            Err(LuaRuntimeError::WrongOwnerThread)
        }
    }

    fn close_registered_task(&mut self, task: TaskToken) -> Result<(), LuaRuntimeError> {
        self.ensure_owner_thread()?;
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
