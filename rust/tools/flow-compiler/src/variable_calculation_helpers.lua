local function __calc_text(value)
  if type(value) ~= 'string' or #value > 4096 or not utf8.len(value) then error('文本须为合法UTF-8且不超过4096字节',0) end
  return value
end
local function __calc_fallback(kind,value,fallback)
  local ok,result=pcall(__calc_convert,kind,value)
  if ok then return result end
  return __calc_convert(kind,fallback)
end
local function __calc_split(source,separator,flags,any)
  __calc_text(source); __calc_text(separator)
  flags=flags or 0
  if math.type(flags) ~= 'integer' or flags < 0 or flags > 3 then error('拆分选项无效',0) end
  if any and separator == '' then error('请选择至少一个分隔字符',0) end
  local parts,size={},2
  local function append(value)
    if flags % 2 == 1 then value=string.match(value,'^%s*(.-)%s*$') end
    if flags >= 2 and value == '' then return end
    if #parts >= 1024 then error('拆分结果最多1024项',0) end
    local encoded='"'..string.gsub(value,'[%z\1-\31\\"]',function(ch)
      if ch == '"' then return '\\"' end
      if ch == '\\' then return '\\\\' end
      return string.format('\\u%04x',string.byte(ch))
    end)..'"'
    size=size+#encoded+(#parts > 0 and 1 or 0)
    if size > 4096 then error('拆分结果超过4096字节，请缩小来源文本',0) end
    parts[#parts+1]=encoded
  end
  if separator == '' then
    for _,code in utf8.codes(source) do append(utf8.char(code)) end
  elseif any then
    local separators={}
    for _,code in utf8.codes(separator) do separators[code]=true end
    local start=1
    for position,code in utf8.codes(source) do
      if separators[code] then append(string.sub(source,start,position-1)); start=position+#utf8.char(code) end
    end
    append(string.sub(source,start))
  else
    local start=1
    while true do
      local first,last=string.find(source,separator,start,true)
      append(string.sub(source,start,first and first-1 or #source))
      if not first then break end
      start=last+1
    end
  end
  return '['..table.concat(parts,',')..']'
end
local function __calc_collection(source)
  __calc_text(source)
  if Data.valid(source) ~= 1 then error('来源不是合法的列表或对象JSON文本',0) end
  local kind=Data.type(source)
  if kind ~= 'array' and kind ~= 'object' then error('请选择列表或对象，不能对普通文本取项',0) end
  return kind
end
local function __calc_item(op,source,key,fallback)
  local kind=__calc_collection(source)
  if kind == 'array' then
    if math.type(key) ~= 'integer' or key < 1 then error('列表序号必须是从1开始的整数',0) end
    key=tostring(key)
  elseif type(key) ~= 'string' or key == '' or #key > 128 or key:find('[%z\1-\31\127]') then
    error('对象字段须为1至128字节且不含控制字符的文本',0)
  end
  local encoded=Data.getJson(source,key)
  if encoded == 'null' then
    if fallback ~= nil then
      if op == 'itemFloat' then return __calc_convert('float',fallback) end
      if op == 'itemJson' and Data.valid(fallback) ~= 1 then error('JSON子项默认值不是合法JSON',0) end
      return fallback
    end
    error('列表序号超出范围、字段不存在或值为空，请检查取值位置或设置默认值',0)
  end
  if op == 'itemJson' then return __calc_text(encoded) end
  local first=string.sub(encoded,1,1)
  if first == '[' or first == '{' then error('取出的内容仍是列表或对象，请选择读取为JSON子项',0) end
  local value=first == '"' and Data.text(encoded) or encoded
  if op == 'itemText' then return __calc_text(value) end
  local conversion=op == 'itemInt' and 'int' or 'float'
  if fallback ~= nil then return __calc_fallback(conversion,value,fallback) end
  return __calc_convert(conversion,value)
end
local function __calc_quick(op,a,b,c)
  if op == 'split' or op == 'splitAny' then return __calc_split(a,b,c,op == 'splitAny') end
  if op == 'listLen' then __calc_collection(a); return Data.length(a) end
  if op == 'itemText' or op == 'itemInt' or op == 'itemFloat' or op == 'itemJson' then return __calc_item(op,a,b,c) end
  if op == 'intOr' or op == 'floatOr' then return __calc_fallback(op == 'intOr' and 'int' or 'float',a,b) end
  __calc_number(a)
  if op == 'min' or op == 'max' then
    __calc_number(b)
    local result=op == 'min' and math.min(a,b) or math.max(a,b)
    if math.type(a) == 'float' or math.type(b) == 'float' then return __calc_number(result+0.0) end
    return result
  end
  if op == 'abs' then
    if math.type(a) == 'integer' and a == math.mininteger then error('整数绝对值超出64位范围',0) end
    return math.abs(a)
  end
  local result
  if math.type(a) == 'integer' then return a end
  if op == 'floor' then result=math.floor(a)
  elseif op == 'ceil' then result=math.ceil(a)
  elseif op == 'round' then result=a >= 0 and math.floor(a+0.5) or math.ceil(a-0.5)
  else error('未知快捷计算操作',0) end
  result=math.tointeger(result)
  if result == nil then error('取整结果超出64位范围',0) end
  return result
end
