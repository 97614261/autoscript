[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [ValidateSet('keyboard', 'full', 'shared-ui', 'landscape', 'glyph-maker', 'glyph-palette')][string]$Suite = 'keyboard',
    [switch]$ConfirmTemporaryImeSwitch,
    [switch]$ConfirmTargetRestart,
    [string]$AdbPath = 'C:\Work\AndroidSDK\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$qaKeyboardAdb = $AdbPath
. (Join-Path $PSScriptRoot 'build-install-mumu.ps1')
. (Join-Path $PSScriptRoot 'device-acceptance-result.ps1')
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid explicit device serial' }
if (-not $ConfirmTemporaryImeSwitch) { throw 'Explicit temporary QA keyboard consent required' }
$qaKeyboard = 'com.autoscript.vision.acceptance/com.autoscript.runtime.service.QaInputMethodService'
function Read-CurrentQaIme {
    Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'secure', 'default_input_method') 10
}
$originalIme = Read-CurrentQaIme
if ($originalIme -notmatch '^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$' -or $originalIme -eq $qaKeyboard) {
    throw 'Original non-QA input method could not be established; no settings changed'
}
if ($Suite -ne 'shared-ui') {
    $names = Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'ps', '-A', '-o', 'NAME') 15
    Assert-StudioAcceptanceIdleTarget -ProcessNames $names -RestartConfirmed ([bool]$ConfirmTargetRestart)
}
# Install nothing, grant no permission, and only enable the fixed test-APK component.
try {
    Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'ime', 'enable', $qaKeyboard) 15 | Write-Output
    Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'ime', 'set', $qaKeyboard) 15 | Write-Output
    if ((Read-CurrentQaIme) -ne $qaKeyboard) { throw 'QA input method selection not confirmed' }
    $arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r')
    if ($Suite -eq 'shared-ui') {
        $arguments += @('-e', 'scriptUiOnly', 'true', 'com.autoscript.vision.acceptance/com.autoscript.runtime.service.VisionAcceptanceInstrumentation')
    } else {
        $arguments += @('-e', $(if ($Suite -eq 'full') { 'finalClosure' } elseif ($Suite -eq 'landscape') { 'landscapeOnly' } elseif ($Suite -eq 'glyph-maker') { 'glyphMakerOnly' } elseif ($Suite -eq 'glyph-palette') { 'glyphPaletteOnly' } else { 'keyboardOnly' }), 'true',
            'com.zhigou.kaifaban.acceptance/com.autoscript.studio.StudioAcceptanceInstrumentation')
    }
    try {
        $output = Invoke-BoundedCli $qaKeyboardAdb $arguments $(if ($Suite -in @('full', 'landscape', 'glyph-maker', 'glyph-palette')) { 420 } else { 180 })
    } catch {
        $qaFailure = $_
        if ($qaFailure.Exception.Message -match 'timed out after' -and ($Suite -eq 'shared-ui' -or $ConfirmTargetRestart)) {
            # Cancelling adb alone does not cancel its Android instrumentation. Stop only
            # this explicitly approved invocation's target, never other apps or the VM.
            $qaTarget = if ($Suite -eq 'shared-ui') { 'com.autoscript.vision.acceptance' } else { 'com.zhigou.kaifaban' }
            Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', $qaTarget) 15 | Write-Output
        }
        throw $qaFailure
    }
    Write-Output $output
    $report = ConvertFrom-StudioAcceptanceOutput $output
    Write-Output "PASS: $Suite with opt-in QA keyboard on $DeviceSerial"
} finally {
    # Do not overwrite a concurrent human choice of a third input method.
    try {
        $currentIme = Read-CurrentQaIme
        if ($currentIme -eq $qaKeyboard) {
            Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'ime', 'set', $originalIme) 15 | Write-Output
        } elseif ($currentIme -ne $originalIme) { throw 'Input method changed externally; original restoration not confirmed' }
    } finally {
        Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'ime', 'disable', $qaKeyboard) 15 | Write-Output
    }
    if ((Read-CurrentQaIme) -ne $originalIme) { throw 'Original input method restoration not confirmed' }
    $enabled = Invoke-BoundedCli $qaKeyboardAdb @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'secure', 'enabled_input_methods') 10
    if (@($enabled.Split(':') | Where-Object { $_.Split(';')[0] -eq $qaKeyboard }).Count -gt 0) { throw 'QA keyboard disabling not confirmed' }
    Write-Output 'Original input method restored; QA keyboard disabled.'
}
