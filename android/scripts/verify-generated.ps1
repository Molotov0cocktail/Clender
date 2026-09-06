param([string]$PythonExecutable = $env:CLENDER_ANDROID_PYTHON)

$ErrorActionPreference = 'Stop'

$ScriptRoot = Split-Path -Parent $PSCommandPath
$AndroidRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptRoot '..'))
$GradleScript = Join-Path $ScriptRoot 'gradle.ps1'

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $GradleScript `
    --offline --no-daemon --dependency-verification strict `
    :app:processDebugMainManifest :app:processReleaseMainManifest verifyDebugApkNoNativeArtifacts
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    throw 'Set CLENDER_ANDROID_PYTHON or pass -PythonExecutable explicitly.'
}
$Python = (Get-Command $PythonExecutable -ErrorAction Stop).Source
& $Python (Join-Path $AndroidRoot 'scripts\verify-foundation.py')
exit $LASTEXITCODE
