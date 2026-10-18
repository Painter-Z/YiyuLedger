[CmdletBinding()]
param(
    [ValidateSet('Debug','Check','ConnectedTest')][string]$Mode = 'Debug',
    [string]$EmulatorSerial = 'emulator-5554',
    [string]$TestClass = '',
    [string]$ReportName = 'android-instrumentation-results.log'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\Android-studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_USER_HOME = 'D:\Android\User'
$env:ANDROID_AVD_HOME = 'D:\Android\Avd'
$env:GRADLE_USER_HOME = 'D:\Android\Gradle'
$env:TEMP = 'D:\Android\Temp'
$env:TMP = $env:TEMP
foreach ($directory in @($env:JAVA_HOME, $env:ANDROID_HOME, $env:GRADLE_USER_HOME, $env:TEMP)) {
    if (!(Test-Path -LiteralPath $directory -PathType Container)) { throw "Missing tool directory: $directory" }
}
[string[]]$tasks = switch ($Mode) {
    'Check' { @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug') }
    'ConnectedTest' { @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    default { @(':app:assembleDebug') }
}
Push-Location (Join-Path $projectRoot 'android')
try {
    & '.\gradlew.bat' @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android build/check failed: $LASTEXITCODE" }
    if ($Mode -eq 'ConnectedTest') {
        if ($EmulatorSerial -notmatch '^emulator-\d+$') { throw 'Automated UI tests are restricted to the development emulator.' }
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        & $adb -s $EmulatorSerial install -r 'app\build\outputs\apk\debug\app-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
        & $adb -s $EmulatorSerial install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
        if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
        if ($ReportName -notmatch '^[a-zA-Z0-9_.-]+\.log$') { throw 'ReportName must be a log filename.' }
        $testArguments = @('-e', 'notClass', 'com.yiyu.ledger.CloudConnectivityTest,com.yiyu.ledger.RecognitionCloudTest,com.yiyu.ledger.CompatibilityUiTest,com.yiyu.ledger.KeyboardOpeningProbeTest,com.yiyu.ledger.LedgerBrowseUiTest')
        if ($TestClass) { $testArguments = @('-e', 'class', $TestClass) }
        $result = & $adb -s $EmulatorSerial shell am instrument -w @testArguments 'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner'
        $result | Set-Content -LiteralPath (Join-Path $projectRoot ('artifacts\' + $ReportName)) -Encoding utf8
        $result | Write-Output
        $textResult = $result -join "`n"
        if ($textResult -notmatch 'OK \(\d+ tests?\)' -or $textResult -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Emulator tests failed; inspect the saved report.' }
    }
    if ($Mode -ne 'ConnectedTest') {
        $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\debug\output-metadata.json' -Raw | ConvertFrom-Json
        $destination = Join-Path $projectRoot ('artifacts\YiyuLedger-' + $metadata.elements[0].versionName + '-android-debug.apk')
        Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $destination -Force
        Get-FileHash -LiteralPath $destination -Algorithm SHA256
    }
} finally { Pop-Location }
