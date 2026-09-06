$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSCommandPath) 'env.ps1')

$AndroidRoot = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSCommandPath) '..'))
$Wrapper = [System.IO.Path]::GetFullPath((Join-Path $AndroidRoot 'gradlew.bat'))
$prefix = $AndroidRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $Wrapper.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Gradle wrapper path escaped the isolated Android project.'
}
if (-not (Test-Path -LiteralPath $Wrapper -PathType Leaf)) {
    throw 'Gradle wrapper is missing. Run the isolated bootstrap script first.'
}

& $Wrapper --project-dir $AndroidRoot "-Duser.home=$env:ANDROID_USER_HOME" @args
exit $LASTEXITCODE
