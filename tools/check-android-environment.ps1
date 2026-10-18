[CmdletBinding()]
param(
    [string]$StudioPath = '',
    [string]$SdkPath = ''
)

$ErrorActionPreference = 'Stop'

function Find-Directory {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return ''
}

$registryRoots = @(
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$registeredStudio = @(Get-ItemProperty -Path $registryRoots -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -match '^Android Studio' -and $_.InstallLocation } |
    ForEach-Object { $_.InstallLocation })

$studioCandidates = @(
    $StudioPath,
    $env:ANDROID_STUDIO_HOME,
    (Join-Path $env:ProgramFiles 'Android\Android Studio'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio'),
    'D:\AndroidStudio',
    'D:\Android-studio',
    'D:\Android Studio',
    'D:\Android\Android Studio'
) + $registeredStudio
$studioDirectory = Find-Directory -Candidates $studioCandidates
$sdkDirectory = Find-Directory -Candidates @(
    $SdkPath, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    'D:\Android\Sdk', 'D:\AndroidSDK', 'D:\Android\android-sdk'
)

$platforms = @()
$buildTools = @()
$adbPath = ''
$sdkManagerPath = ''
if ($sdkDirectory) {
    $platforms = @(Get-ChildItem -LiteralPath (Join-Path $sdkDirectory 'platforms') -Directory -ErrorAction SilentlyContinue | ForEach-Object Name)
    $buildTools = @(Get-ChildItem -LiteralPath (Join-Path $sdkDirectory 'build-tools') -Directory -ErrorAction SilentlyContinue | ForEach-Object Name)
    $candidateAdb = Join-Path $sdkDirectory 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidateAdb -PathType Leaf) { $adbPath = $candidateAdb }
    $commandLineRoot = Join-Path $sdkDirectory 'cmdline-tools'
    foreach ($directory in @(Get-ChildItem -LiteralPath $commandLineRoot -Directory -ErrorAction SilentlyContinue)) {
        $candidateManager = Join-Path $directory.FullName 'bin\sdkmanager.bat'
        if (Test-Path -LiteralPath $candidateManager -PathType Leaf) { $sdkManagerPath = $candidateManager; break }
    }
}
if (!$adbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adbPath = $adbCommand.Source }
}

$javaCandidates = @()
if ($studioDirectory) { $javaCandidates += Join-Path $studioDirectory 'jbr\bin\java.exe' }
if ($env:JAVA_HOME) { $javaCandidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) { $javaCandidates += $javaCommand.Source }
$javaPath = @($javaCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1)
$otherIdeJava = Test-Path -LiteralPath 'D:\DevEcoStudio\DevEco Studio\jbr\bin\java.exe' -PathType Leaf
$projectRoot = Split-Path $PSScriptRoot -Parent
$projectPresent = (Test-Path -LiteralPath (Join-Path $projectRoot 'android\app\build.gradle.kts')) -or
    (Test-Path -LiteralPath (Join-Path $projectRoot 'android\app\build.gradle'))
$wrapperPresent = Test-Path -LiteralPath (Join-Path $projectRoot 'android\gradlew.bat')
$api37Present = @($platforms | Where-Object { $_ -match '^android-37(?:\.0)?$' }).Count -gt 0
$buildTools37Present = @($buildTools | Where-Object { $_ -match '^37\.' }).Count -gt 0

# Inventory only: no downloads, credential access, ADB server start, or device operations.
[PSCustomObject]@{
    CheckedAtUtc = [DateTime]::UtcNow.ToString('o')
    AndroidStudio = $studioDirectory
    AndroidSdk = $sdkDirectory
    InstalledPlatforms = @($platforms)
    InstalledBuildTools = @($buildTools)
    Api37Available = $api37Present
    BuildTools37Available = $buildTools37Present
    Adb = $adbPath
    SdkManager = $sdkManagerPath
    Java = @($javaPath)
    DevEcoBundledJavaAvailable = $otherIdeJava
    AndroidProjectPresent = $projectPresent
    GradleWrapperPresent = $wrapperPresent
    BuildPrerequisitesDetected = [bool]($projectPresent -and $wrapperPresent -and $sdkDirectory -and $javaPath.Count -gt 0 -and $api37Present -and $buildTools37Present)
    Note = 'Path detection is not a build or a compatibility check. Custom installations can be supplied with -StudioPath and -SdkPath.'
} | ConvertTo-Json -Depth 4
