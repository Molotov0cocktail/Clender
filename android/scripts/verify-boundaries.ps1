param([string]$PythonExecutable = $env:CLENDER_ANDROID_PYTHON)

$ErrorActionPreference = 'Stop'
$AndroidRoot = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSCommandPath) '..'))
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $AndroidRoot '..'))
if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    throw 'Set CLENDER_ANDROID_PYTHON or pass -PythonExecutable explicitly.'
}
$Python = (Get-Command $PythonExecutable -ErrorAction Stop).Source

& $Python (Join-Path $AndroidRoot 'scripts\verify-foundation.py')
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$forbidden = git -C $RepoRoot ls-files -- 'android/*.apk' 'android/*.aab' 'android/*.jks' 'android/*.keystore' 'android/**/build/**'
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect tracked Android artifacts.'
}
if ($forbidden) {
    throw 'Generated Android artifacts or signing material are tracked.'
}

Write-Host 'Android boundary verification passed.'
