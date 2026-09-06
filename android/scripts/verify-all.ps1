param([string]$PythonExecutable = $env:CLENDER_ANDROID_PYTHON)
$ErrorActionPreference = 'Stop'
try {
    if ([string]::IsNullOrWhiteSpace($PythonExecutable)) { throw 'Python executable is required.' }
    $ReleaseScriptRoot = $PSScriptRoot
    . (Join-Path $ReleaseScriptRoot 'env.ps1')
    Push-Location $AndroidRoot
    try {
        & $PythonExecutable -B -m unittest discover -s tests/release -v
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ReleaseScriptRoot 'gradle.ps1') `
            --offline --no-daemon --max-workers=1 --dependency-verification strict `
            testDebugUnitTest lintDebug lintRelease detekt ktlintCheck `
            verifyReleaseSigningPolicy verifyNoGoogleServices verifyNoNativeRuntimeArtifacts `
            verifyResolvedVersionsLocked assembleDebug verifyDebugApkNoNativeArtifacts `
            :app:processDebugMainManifest :app:processReleaseMainManifest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & $PythonExecutable -B (Join-Path $ReleaseScriptRoot 'verify-foundation.py')
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ReleaseScriptRoot 'verify-boundaries.ps1') -PythonExecutable $PythonExecutable
        exit $LASTEXITCODE
    } finally { Pop-Location }
} catch {
    Write-Host 'Release verification could not complete; sensitive details suppressed.'
    exit 1
}
