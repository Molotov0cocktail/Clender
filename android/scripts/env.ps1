$ErrorActionPreference = 'Stop'

$ScriptRoot = Split-Path -Parent $PSCommandPath
$AndroidRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptRoot '..'))

function Resolve-IsolatedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $candidate = [System.IO.Path]::GetFullPath((Join-Path $AndroidRoot $RelativePath))
    $prefix = $AndroidRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Android toolchain path escaped the isolated project directory.'
    }
    New-Item -ItemType Directory -Force -Path $candidate | Out-Null
    return (Resolve-Path -LiteralPath $candidate).Path
}

$env:JAVA_HOME = Resolve-IsolatedPath '.toolchain\jdk-17.0.20+8'
$JavaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) {
    throw 'The isolated Android JDK is missing. Run the documented toolchain bootstrap first.'
}
$env:ANDROID_SDK_ROOT = Resolve-IsolatedPath '.sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:ANDROID_USER_HOME = Resolve-IsolatedPath '.android'
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Resolve-IsolatedPath '.android\avd'
$env:GRADLE_USER_HOME = Resolve-IsolatedPath '.gradle'
$env:TEMP = Resolve-IsolatedPath '.tmp'
$env:TMP = $env:TEMP
$env:JAVA_OPTS = "-Duser.home=`"$env:ANDROID_USER_HOME`""
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin;$env:Path"
