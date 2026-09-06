param(
    [string]$PythonExecutable = $env:CLENDER_ANDROID_PYTHON,
    [string]$Bundletool = '.toolchain/bundletool-all.jar'
)
$ErrorActionPreference = 'Stop'
try {
    if ([string]::IsNullOrWhiteSpace($PythonExecutable)) { throw 'Python executable is required.' }
    $ReleaseScriptRoot = $PSScriptRoot
    . (Join-Path $ReleaseScriptRoot 'env.ps1')
    Push-Location $AndroidRoot
    try {
        & $PythonExecutable -B (Join-Path $ReleaseScriptRoot 'verify-release.py') signing-check
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        # Gradle/keytool failures can contain aliases or credential paths. Do not echo.
        # Windows PowerShell represents redirected stderr as NativeCommandError.
        # Inspect the native exit code instead of letting Stop replace it with 1.
        $ReleaseErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ReleaseScriptRoot 'gradle.ps1') `
                --offline --no-daemon --max-workers=1 --dependency-verification strict `
                :app:assembleRelease :app:bundleRelease 2>&1 | Out-Null
            $ReleaseExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $ReleaseErrorPreference }
        if ($ReleaseExitCode -ne 0) {
            Write-Host 'Release build failed; sensitive tool output suppressed.'
            exit $ReleaseExitCode
        }
        & $PythonExecutable -B (Join-Path $ReleaseScriptRoot 'verify-release.py') verify --bundletool $Bundletool
        exit $LASTEXITCODE
    } finally { Pop-Location }
} catch {
    Write-Host 'Release build could not complete; sensitive details suppressed.'
    exit 1
}
