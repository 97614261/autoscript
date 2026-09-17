# 临时：PowerShell 脚本没有任何编译期检查，这里用解析器做一次语法体检。
param([string[]]$Path = @("scripts/m27-ui-smoke.ps1", "scripts/m27-gate.ps1"))

$failed = $false
foreach ($file in $Path) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        (Resolve-Path $file).Path, [ref]$tokens, [ref]$errors)
    if ($errors -and $errors.Count -gt 0) {
        $failed = $true
        foreach ($e in $errors) { "FAIL $file : $($e.Extent.StartLineNumber) : $($e.Message)" }
    } else {
        "OK   $file"
    }
}
if ($failed) { exit 1 }
