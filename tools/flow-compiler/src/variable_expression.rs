//! Bounded, typed expression grammar. Source text is never emitted as Lua.
use flow_ir::ProjectVariableType;
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Kind {
    Integer,
    Number,
    String,
}

#[derive(Debug, Clone)]
enum Expr {
    Literal(Value, Kind),
    Variable(String),
    Unary(char, Box<Expr>),
    Binary(&'static str, Box<Expr>, Box<Expr>),
    Convert(String, Box<Expr>),
}

struct Parser<'a> {
    source: &'a str,
    pos: usize,
    nodes: usize,
}

impl Parser<'_> {
    fn space(&mut self) {
        while self
            .source
            .as_bytes()
            .get(self.pos)
            .is_some_and(u8::is_ascii_whitespace)
        {
            self.pos += 1;
        }
    }
    fn take(&mut self, value: &str) -> bool {
        self.space();
        if self.source[self.pos..].starts_with(value) {
            self.pos += value.len();
            true
        } else {
            false
        }
    }
    fn count(&mut self) -> Result<(), &'static str> {
        self.nodes += 1;
        if self.nodes > 128 {
            Err("计算表达式最多128项")
        } else {
            Ok(())
        }
    }
    fn expression(&mut self, minimum: u8, depth: usize) -> Result<Expr, &'static str> {
        if depth > 16 {
            return Err("计算表达式嵌套最多16层");
        }
        self.space();
        self.count()?;
        let mut left = if self.take("(") {
            let value = self.expression(0, depth + 1)?;
            if !self.take(")") {
                return Err("计算表达式缺少右括号");
            }
            value
        } else if self.take("-") {
            self.space();
            if self.source[self.pos..].starts_with("9223372036854775808")
                && !self
                    .source
                    .as_bytes()
                    .get(self.pos + 19)
                    .is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'.' || *b == b'_')
            {
                self.pos += 19;
                Expr::Literal(Value::from(i64::MIN), Kind::Integer)
            } else {
                Expr::Unary('-', Box::new(self.expression(4, depth + 1)?))
            }
        } else if self.take("+") {
            Expr::Unary('+', Box::new(self.expression(4, depth + 1)?))
        } else {
            self.atom(depth)?
        };
        loop {
            self.space();
            let rest = &self.source[self.pos..];
            let op = [
                ("..", 1),
                ("+", 2),
                ("-", 2),
                ("//", 3),
                ("*", 3),
                ("/", 3),
                ("%", 3),
            ]
            .into_iter()
            .find(|(op, _)| rest.starts_with(op));
            let Some((op, precedence)) = op else {
                break;
            };
            if precedence < minimum {
                break;
            }
            self.pos += op.len();
            self.count()?;
            let right = self.expression(precedence + 1, depth + 1)?;
            left = Expr::Binary(op, Box::new(left), Box::new(right));
        }
        Ok(left)
    }
    fn atom(&mut self, depth: usize) -> Result<Expr, &'static str> {
        self.space();
        let start = self.pos;
        let bytes = self.source.as_bytes();
        if bytes.get(start) == Some(&b'"') {
            self.pos += 1;
            let mut escaped = false;
            while let Some(byte) = bytes.get(self.pos) {
                self.pos += 1;
                if *byte == b'"' && !escaped {
                    let value: String = serde_json::from_str(&self.source[start..self.pos])
                        .map_err(|_| "字符常量请使用JSON双引号与合法转义")?;
                    return Ok(Expr::Literal(Value::String(value), Kind::String));
                }
                if escaped {
                    escaped = false;
                } else {
                    escaped = *byte == b'\\';
                }
            }
            return Err("字符常量缺少结束双引号");
        }
        if bytes.get(start).is_some_and(u8::is_ascii_digit) {
            while bytes.get(self.pos).is_some_and(u8::is_ascii_digit) {
                self.pos += 1;
            }
            if bytes.get(self.pos) == Some(&b'.') && bytes.get(self.pos + 1) != Some(&b'.') {
                self.pos += 1;
                while bytes.get(self.pos).is_some_and(u8::is_ascii_digit) {
                    self.pos += 1;
                }
            }
            if matches!(bytes.get(self.pos), Some(b'e' | b'E')) {
                self.pos += 1;
                if matches!(bytes.get(self.pos), Some(b'+' | b'-')) {
                    self.pos += 1;
                }
                while bytes.get(self.pos).is_some_and(u8::is_ascii_digit) {
                    self.pos += 1;
                }
            }
            let token = &self.source[start..self.pos];
            let value: Value = serde_json::from_str(token).map_err(|_| "数值常量无效或越界")?;
            let number = value.as_number().ok_or("数值常量无效")?;
            let kind = if token.contains(['.', 'e', 'E']) {
                Kind::Number
            } else {
                if number.as_i64().is_none() {
                    return Err("整数常量超出64位范围");
                }
                Kind::Integer
            };
            return Ok(Expr::Literal(value, kind));
        }
        if bytes
            .get(start)
            .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_')
        {
            self.pos += 1;
            while bytes
                .get(self.pos)
                .is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'_')
            {
                self.pos += 1;
            }
            let name = self.source[start..self.pos].to_owned();
            if name.len() > 64 {
                return Err("变量名过长");
            }
            if self.take("(") {
                if !matches!(name.as_str(), "int" | "float" | "text") {
                    return Err("只允许int/float/text类型转换");
                }
                let value = self.expression(0, depth + 1)?;
                if !self.take(")") {
                    return Err("类型转换只能包含一个参数");
                }
                return Ok(Expr::Convert(name, Box::new(value)));
            }
            return Ok(Expr::Variable(name));
        }
        Err("只允许数值、双引号字符、变量、括号与白名单运算符")
    }
}

fn parse(source: &str) -> Result<Expr, &'static str> {
    if source.is_empty() || source.len() > 1024 {
        return Err("计算表达式须为1至1024字节");
    }
    let mut parser = Parser {
        source,
        pos: 0,
        nodes: 0,
    };
    let expression = parser.expression(0, 0)?;
    parser.space();
    if parser.pos != source.len() {
        return Err("计算表达式包含未支持的语法");
    }
    Ok(expression)
}

fn kind_of(kind: ProjectVariableType) -> Result<Kind, &'static str> {
    match kind {
        ProjectVariableType::Integer => Ok(Kind::Integer),
        ProjectVariableType::Number => Ok(Kind::Number),
        ProjectVariableType::String => Ok(Kind::String),
        ProjectVariableType::Image => Err("图像变量不能参与标量计算或转换"),
    }
}

impl Expr {
    fn kind(
        &self,
        lookup: &impl Fn(&str) -> Option<ProjectVariableType>,
    ) -> Result<Kind, &'static str> {
        match self {
            Self::Literal(_, kind) => Ok(*kind),
            Self::Variable(name) => {
                kind_of(lookup(name).ok_or("计算来源变量未声明或不在当前插件作用域")?)
            }
            Self::Unary(_, value) => {
                let kind = value.kind(lookup)?;
                if kind == Kind::String {
                    Err("字符不能进行数值运算")
                } else {
                    Ok(kind)
                }
            }
            Self::Convert(name, value) => {
                value.kind(lookup)?;
                Ok(match name.as_str() {
                    "int" => Kind::Integer,
                    "float" => Kind::Number,
                    _ => Kind::String,
                })
            }
            Self::Binary(op, left, right) => {
                let left = left.kind(lookup)?;
                let right = right.kind(lookup)?;
                if *op == ".." {
                    return if left == Kind::String && right == Kind::String {
                        Ok(Kind::String)
                    } else {
                        Err("字符连接需要字符类型，请先用text转换")
                    };
                }
                if left == Kind::String || right == Kind::String {
                    return Err("算术运算只能使用整数或浮点");
                }
                if *op == "/" || left == Kind::Number || right == Kind::Number {
                    Ok(Kind::Number)
                } else {
                    Ok(Kind::Integer)
                }
            }
        }
    }
    fn lua(&self) -> String {
        match self {
            Self::Literal(Value::String(value), _) => quote_lua(value),
            Self::Literal(Value::Number(value), Kind::Integer)
                if value.as_i64() == Some(i64::MIN) =>
            {
                "math.mininteger".to_owned()
            }
            Self::Literal(value, _) => serde_json::to_string(value).expect("JSON scalar"),
            Self::Variable(name) => {
                let name = serde_json::to_string(name).expect("name");
                format!(
                    "__calc_source(__vars[{name}],__local_types[{name}] or __global_types[{name}])"
                )
            }
            Self::Unary(op, value) => format!("__calc_unary('{}',{})", op, value.lua()),
            Self::Binary(op, left, right) => {
                format!("__calc_binary('{}',{}, {})", op, left.lua(), right.lua())
            }
            Self::Convert(name, value) => format!("__calc_convert('{}',{})", name, value.lua()),
        }
    }
}

fn quote_lua(value: &str) -> String {
    let mut result = String::from("\"");
    for ch in value.chars() {
        match ch {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\u{0}'..='\u{1f}' | '\u{7f}' => result.push_str(&format!("\\{:03}", u32::from(ch))),
            _ => result.push(ch),
        }
    }
    result.push('"');
    result
}

pub(crate) fn validate(
    source: &str,
    target: Option<ProjectVariableType>,
    lookup: impl Fn(&str) -> Option<ProjectVariableType>,
) -> Result<(), &'static str> {
    let target = kind_of(target.ok_or("计算目标变量未声明或不在当前插件作用域")?)?;
    let actual = parse(source)?.kind(&lookup)?;
    if target == actual || target == Kind::Number && actual == Kind::Integer {
        Ok(())
    } else {
        Err("计算结果与目标变量类型不匹配，请显式转换")
    }
}

pub(crate) fn render(source: &str) -> Result<String, &'static str> {
    Ok(parse(source)?.lua())
}

pub(crate) const LUA_HELPERS: &str = r#"local function __calc_number(value)
  if type(value) ~= 'number' or value ~= value or value == math.huge or value == -math.huge then error('计算需要有限数值',0) end
  return value
end
local function __calc_source(value,kind)
  if kind == 'string' then
    if type(value) ~= 'string' or #value > 4096 then error('计算字符来源无效或超过4096字节',0) end
    return value
  end
  __calc_number(value)
  if kind == 'integer' then local result=math.tointeger(value); if result == nil then error('计算整数来源超出64位范围',0) end; return result end
  if kind == 'number' then return __calc_number(value + 0.0) end
  error('计算来源缺少标量类型声明',0)
end
local function __calc_unary(op,value)
  __calc_number(value)
  if op == '+' then return value end
  if math.type(value) == 'integer' and value == math.mininteger then error('整数计算溢出',0) end
  return -value
end
local function __calc_convert(kind,value)
  if type(value) ~= 'number' and type(value) ~= 'string' then error('转换只接受数值或字符',0) end
  if type(value) == 'number' then __calc_number(value) end
  if kind == 'text' then local result=tostring(value); if #result > 4096 then error('字符结果超过4096字节',0) end; return result end
  if type(value) == 'string' and not (value:match('^%s*[+-]?%d+%.?%d*[eE]?[+-]?%d*%s*$') or value:match('^%s*[+-]?%.%d+[eE]?[+-]?%d*%s*$')) then error('字符不是十进制数值',0) end
  local result=__calc_number(tonumber(value))
  if kind == 'float' then return __calc_number(result + 0.0) end
  result=math.tointeger(math.modf(result))
  if result == nil then error('转换整数超出64位范围',0) end
  return result
end
local function __calc_binary(op,a,b)
  if op == '..' then
    if type(a) ~= 'string' or type(b) ~= 'string' then error('连接需要字符类型',0) end
    if #a + #b > 4096 then error('字符结果超过4096字节',0) end
    return a .. b
  end
  __calc_number(a); __calc_number(b)
  if (op == '/' or op == '//' or op == '%') and b == 0 then error('计算不能除以零',0) end
  local integers=math.type(a) == 'integer' and math.type(b) == 'integer'
  if integers and (op == '*' or op == '//') and ((a == math.mininteger and b == -1) or (op == '*' and b == math.mininteger and a == -1)) then error('整数计算溢出',0) end
  local r
  if op == '+' then r=a+b elseif op == '-' then r=a-b elseif op == '*' then r=a*b elseif op == '/' then r=a/b elseif op == '//' then r=a//b else r=a%b end
  if integers then
    if op == '+' and ((b > 0 and r < a) or (b < 0 and r > a)) then error('整数计算溢出',0) end
    if op == '-' and ((b < 0 and r < a) or (b > 0 and r > a)) then error('整数计算溢出',0) end
    if op == '*' and b ~= 0 and r//b ~= a then error('整数计算溢出',0) end
  end
  return __calc_number(r)
end"#;

#[cfg(test)]
mod tests {
    use super::*;
    fn lookup(name: &str) -> Option<ProjectVariableType> {
        match name {
            "count" => Some(ProjectVariableType::Integer),
            "ratio" => Some(ProjectVariableType::Number),
            "label" => Some(ProjectVariableType::String),
            "frame" => Some(ProjectVariableType::Image),
            _ => None,
        }
    }
    #[test]
    fn arithmetic_conversion_and_precedence() {
        for source in [
            "count + 2 * (3 - 1)",
            "count % 3",
            "int(ratio / 2)",
            "count // 2",
            "int(\"12\")",
            "-count",
        ] {
            assert_eq!(
                validate(source, Some(ProjectVariableType::Integer), lookup),
                Ok(()),
                "{source}"
            );
        }
        assert!(validate("count / 2", Some(ProjectVariableType::Integer), lookup).is_err());
        assert!(validate("count / 2", Some(ProjectVariableType::Number), lookup).is_ok());
        assert!(validate(
            "label .. text(count)",
            Some(ProjectVariableType::String),
            lookup
        )
        .is_ok());
        assert!(render("count + 2 * 3")
            .unwrap()
            .contains("__calc_binary('*',2, 3)"));
    }
    #[test]
    fn rejects_escape_wrong_types_and_limits() {
        for source in [
            "os.execute(\"x\")",
            "count; return 1",
            "count[1]",
            "count.foo",
            "unknown + 1",
            "label + 1",
            "text(frame)",
            "count .. label",
            "1e999",
            "9223372036854775808",
            "int(1,2)",
        ] {
            assert!(
                validate(source, Some(ProjectVariableType::Integer), lookup).is_err(),
                "{source}"
            );
        }
        assert!(parse(&"(".repeat(17)).is_err());
        assert!(parse(&"1+".repeat(200)).is_err());
        assert!(parse(&"a".repeat(1025)).is_err());
        assert!(render("\"中文\\n\\\"\"").is_ok());
    }
}
