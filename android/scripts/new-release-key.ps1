param(
    [string]$PythonExecutable = $env:CLENDER_ANDROID_PYTHON,
    [string]$KeyFile = 'release/clender-release.jks'
)
$ErrorActionPreference = 'Stop'
try {
    if ([string]::IsNullOrWhiteSpace($PythonExecutable)) { throw 'Python executable is required.' }
    $ReleaseScriptRoot = $PSScriptRoot
    . (Join-Path $ReleaseScriptRoot 'env.ps1')
    & $PythonExecutable -B (Join-Path $ReleaseScriptRoot 'verify-release.py') new-key --key $KeyFile
    $ReleaseExitCode = $LASTEXITCODE
    if ($ReleaseExitCode -eq 0) {
        Write-Host 'Independent release signing created. Back up the key and signing configuration securely.'
    }
    exit $ReleaseExitCode
} catch {
    Write-Host 'Signing creation failed; sensitive details suppressed.'
    exit 1
}
