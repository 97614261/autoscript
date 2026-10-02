use flow_ir::PopupStyle;
use std::collections::BTreeMap;
use std::fmt::Write;

/// Freeze project presentation into Lua; explicit per-call styles still take precedence.
/// # Errors
/// Returns JSON serialization failures.
pub fn popup_style_prefix(style: &PopupStyle) -> Result<String, serde_json::Error> {
    let json = serde_json::to_string(style)?;
    let literal = serde_json::to_string(&json)?;
    Ok(format!("-- @autoscript project popup style\nlocal __autoscript_toast_impl = Prompt.toast\nPrompt.toast = function(message, styleJson) return __autoscript_toast_impl(message, styleJson or {literal}) end\n"))
}

/// Bundle only declared project modules behind lexical require, never filesystem require.
/// # Errors
/// Returns missing/invalid/ambiguous module errors.
pub fn bundle_lua_project(
    entry: &str,
    sources: &BTreeMap<String, String>,
) -> Result<String, String> {
    let entry_source = sources.get(entry).ok_or("Lua entry is not declared")?;
    if sources.len() == 1 {
        return Ok(entry_source.clone());
    }
    let mut output = String::from("-- @autoscript bundled Lua project; do not edit while running\nlocal require\nlocal __autoscript_modules = {\n");
    let mut names = std::collections::HashSet::new();
    for (path, source) in sources.iter().filter(|(path, _)| path.as_str() != entry) {
        let name = path
            .strip_prefix("lua/")
            .and_then(|s| s.strip_suffix(".lua"))
            .ok_or_else(|| format!("Invalid Lua module path: {path}"))?
            .replace('/', ".");
        if !names.insert(name.clone()) {
            return Err(format!("Ambiguous Lua module: {name}"));
        }
        let literal = serde_json::to_string(&name).map_err(|e| e.to_string())?;
        write!(output, "  [{literal}] = function()\n{source}\n  end,\n")
            .map_err(|e| e.to_string())?;
    }
    output.push_str("}\nlocal __autoscript_loaded = {}\nlocal __autoscript_loading = {}\nrequire = function(name)\n  if __autoscript_loaded[name] ~= nil then return __autoscript_loaded[name] end\n  local loader = __autoscript_modules[name]\n  if loader == nil then error('Lua 模块不存在: ' .. tostring(name), 2) end\n  if __autoscript_loading[name] then error('Lua 模块循环依赖: ' .. tostring(name), 2) end\n  __autoscript_loading[name] = true\n  local ok, value = pcall(loader)\n  __autoscript_loading[name] = nil\n  if not ok then error(value, 0) end\n  if value == nil then value = true end\n  __autoscript_loaded[name] = value\n  return value\nend\n");
    output.push_str(entry_source);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_require_is_lexical_cached_and_rejects_cycles_and_external_modules() {
        let sources = BTreeMap::from([
            ("main.lua".into(), "local a=require('a'); assert(a==require('a')); return function() return a.value end".into()),
            ("lua/a.lua".into(), "loads=(loads or 0)+1; local b=require('b'); return {value=b}".into()),
            ("lua/b.lua".into(), "return 42".into()),
        ]);
        let lua = mlua::Lua::new();
        let entry: mlua::Function = lua
            .load(bundle_lua_project("main.lua", &sources).unwrap())
            .eval()
            .unwrap();
        assert_eq!(entry.call::<i64>(()).unwrap(), 42);
        assert_eq!(lua.globals().get::<i64>("loads").unwrap(), 1);
        let mut cyclic = sources.clone();
        cyclic.insert("lua/b.lua".into(), "return require('a')".into());
        let error = lua
            .load(bundle_lua_project("main.lua", &cyclic).unwrap())
            .exec()
            .unwrap_err();
        assert!(error.to_string().contains("循环依赖"));
        cyclic.insert("lua/b.lua".into(), "return require('outside')".into());
        assert!(lua
            .load(bundle_lua_project("main.lua", &cyclic).unwrap())
            .exec()
            .unwrap_err()
            .to_string()
            .contains("模块不存在"));
    }

    #[test]
    fn frozen_popup_style_keeps_pixels_and_explicit_override() {
        let style = PopupStyle {
            width_px: 100,
            height_px: 30,
            ..PopupStyle::default()
        };
        let lua = mlua::Lua::new();
        lua.load("Prompt={toast=function(message,style) last_style=style end}")
            .exec()
            .unwrap();
        lua.load(popup_style_prefix(&style).unwrap())
            .exec()
            .unwrap();
        lua.load("Prompt.toast('small')").exec().unwrap();
        let value: String = lua.globals().get("last_style").unwrap();
        let frozen: PopupStyle = serde_json::from_str(&value).unwrap();
        assert_eq!(frozen, style);
        lua.load("Prompt.toast('custom','explicit')")
            .exec()
            .unwrap();
        assert_eq!(
            lua.globals().get::<String>("last_style").unwrap(),
            "explicit"
        );
    }
}
