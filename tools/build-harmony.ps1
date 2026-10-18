param([string]$StudioPath = 'D:\DevEcoStudio\DevEco Studio', [string]$SdkPath = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$node = Join-Path $StudioPath 'tools\node\node.exe'
$hvigor = Join-Path $StudioPath 'tools\hvigor\bin\hvigorw.js'
$ohpm = Join-Path $StudioPath 'tools\ohpm\bin\pm-cli.js'
foreach ($file in @($node, $hvigor, $ohpm)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing DevEco tool: $file" }
}
$env:NODE_HOME = Join-Path $StudioPath 'tools\node'
$env:JAVA_HOME = Join-Path $StudioPath 'jbr'
$env:DEVECO_SDK_HOME = if ($SdkPath) { $SdkPath } else { Join-Path $StudioPath 'sdk' }
$previewDependencies = Join-Path $env:DEVECO_SDK_HOME 'default\openharmony\previewer\common\bin'
$hmsDependencies = Join-Path $env:DEVECO_SDK_HOME 'default\hms\toolchains\lib'
$nativeRuntime = Join-Path $StudioPath 'tools\emulator'
$env:PATH = "$env:NODE_HOME;$previewDependencies;$hmsDependencies;$nativeRuntime;$env:JAVA_HOME\bin;$env:PATH"
Push-Location (Join-Path $root 'harmony')
$previousNodeOptions = $env:NODE_OPTIONS
$previousResourceRuntime = $env:YIYU_RESOURCE_RUNTIME
try {
    & $node $ohpm install
    if ($LASTEXITCODE -ne 0) { throw 'ohpm install failed' }
    # The SDK image extension needs DLLs from the previewer. Some Windows loaders
    # ignore PATH for these dependencies, so prepare an isolated application directory.
    $resourceRuntime = Join-Path $root 'artifacts\harmony-resource-runtime'
    New-Item -ItemType Directory -Path $resourceRuntime -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $env:DEVECO_SDK_HOME 'default\openharmony\toolchains\restool.exe') -Destination $resourceRuntime -Force
    foreach ($dependency in @('libastc_encoder_shared.dll','libimage_transcoder_shared.dll','liblz4_shared.dll','libtextureSuperCompress.dll')) {
        Copy-Item -LiteralPath (Join-Path $hmsDependencies $dependency) -Destination $resourceRuntime -Force
    }
    foreach ($dependency in @('libcjson.dll','libexpat.dll','libhilog.dll','libhmicuuc.dll','libjsoncpp.dll','libsec_shared.dll','libskia_canvaskit.dll')) {
        Copy-Item -LiteralPath (Join-Path $previewDependencies $dependency) -Destination $resourceRuntime -Force
    }
    $env:YIYU_RESOURCE_RUNTIME = $resourceRuntime
    $buildPreload = (Join-Path $PSScriptRoot 'hvigor-env.cjs').Replace('\', '/')
    $env:NODE_OPTIONS = ($previousNodeOptions + ' --require="' + $buildPreload + '"').Trim()
    & $node $hvigor --mode module -p product=default -p module=entry@default -p buildMode=debug assembleHap --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'HarmonyOS build failed' }
} finally { $env:NODE_OPTIONS = $previousNodeOptions; $env:YIYU_RESOURCE_RUNTIME = $previousResourceRuntime; Pop-Location }
