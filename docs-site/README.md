# AutoScript离线能力文档

这里是独立于Android App的HTML文档项目。`generated/`内容由Rust `api-codegen`从`schema/api-schema/functions/`生成并签入仓库，禁止手工修改。

- `generated/api.html`：脚本API能力目录。
- `glyph-dictionary.html`：基础字库格式、制作和项目接入说明。

```powershell
cargo run -p api-codegen -- generate .
cargo run -p api-codegen -- check .
```
