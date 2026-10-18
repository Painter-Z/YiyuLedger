[CmdletBinding()]
param(
    [ValidateSet(35,36,37)][int]$Api = 37,
    [ValidateRange(5554,5584)][int]$Port = 5554,
    [string]$EmulatorPath = '',
    [string]$Gpu = '',
    [switch]$Foreground
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\Android-studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_USER_HOME = 'D:\Android\User'
$env:ANDROID_AVD_HOME = 'D:\Android\Avd'
$env:TEMP = 'D:\Android\Temp'
$env:TMP = $env:TEMP
if ($Port % 2 -ne 0) { throw 'The emulator console port must be even.' }
$stableRuntime = 'D:\Android\Tools\emulator-36.6.11\emulator'
if (!$EmulatorPath) {
    $EmulatorPath = if ($Api -in @(35,36) -and (Test-Path -LiteralPath (Join-Path $stableRuntime 'emulator.exe'))) { Join-Path $stableRuntime 'emulator.exe' } else { 'D:\Android\Sdk\emulator\emulator.exe' }
}
if (!(Test-Path -LiteralPath $EmulatorPath -PathType Leaf)) { throw "Emulator executable missing: $EmulatorPath" }
$emulatorDirectory = Split-Path $EmulatorPath -Parent
$useDirectQemu = $emulatorDirectory -eq $stableRuntime
if (!$Gpu) { $Gpu = if ($useDirectQemu) { 'off' } else { 'swiftshader_indirect' } }
$env:ANDROID_EMULATOR_LAUNCHER_DIR = $emulatorDirectory
$env:Path = $emulatorDirectory + ';' + (Join-Path $emulatorDirectory 'lib64') + ';' + (Join-Path $emulatorDirectory 'lib64\gles_swiftshader') + ';' + $env:Path
$avdName = "Yiyu_API$Api"
$avdDirectory = "D:\Android\Avd\$avdName.avd"
$platform = if ($Api -eq 37) { '37.0' } else { "$Api" }
if (!(Test-Path -LiteralPath "D:\Android\Sdk\system-images\android-$platform\google_apis\x86_64\package.xml")) {
    throw "Android API $Api emulator image has not finished installing."
}
if (!(Test-Path -LiteralPath (Join-Path $avdDirectory 'config.ini'))) {
    'no' | & 'D:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat' create avd -n $avdName -k "system-images;android-$platform;google_apis;x86_64" -d pixel_7 --path $avdDirectory
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
}
$existing = Get-CimInstance Win32_Process | Where-Object { $_.Name -like '*qemu*' -and $_.CommandLine -like "*$avdName*" }
if ($existing) { "$avdName emulator is already running."; return }
$executable = if ($useDirectQemu) { Join-Path $emulatorDirectory 'qemu\windows-x86_64\qemu-system-x86_64-headless.exe' } else { $EmulatorPath }
$arguments = @('-avd',$avdName,'-port',"$Port",'-no-window','-no-snapshot','-no-boot-anim','-no-audio','-gpu',$Gpu,'-feature','-Vulkan','-memory','4096','-cores','2')
if ($Foreground) {
    & $executable @arguments *> (Join-Path $projectRoot "artifacts\android$Api-emulator.stdout.log")
    if ($LASTEXITCODE -ne 0) { throw "Emulator failed: $LASTEXITCODE" }
    return
}
$process = Start-Process -FilePath $executable -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot "artifacts\android$Api-emulator.stdout.log") -RedirectStandardError (Join-Path $projectRoot "artifacts\android$Api-emulator.stderr.log")
Write-Output "Headless development emulator starting (launcher PID $($process.Id)); AVD files remain on D:."
