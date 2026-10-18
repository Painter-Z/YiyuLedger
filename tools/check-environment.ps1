$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$paths = @(@(
    $env:DEVECO_CLI_STUDIO_PATH,
    $env:DEVECO_HOME,
    'C:\Program Files\Huawei\DevEco Studio',
    'C:\Program Files\Huawei\DevEco Studio Next',
    'D:\DevEco Studio'
    'D:\DevEcoStudio\DevEco Studio'
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
$sdkPath = if ($paths.Count -gt 0) { Join-Path $paths[0] 'sdk' } else { '' }
$hdcPath = if ($sdkPath) { Join-Path $sdkPath 'default\openharmony\toolchains\hdc.exe' } else { '' }
$keyPresent = $false
$envPath = Join-Path $root 'backend\.env'
if (Test-Path -LiteralPath $envPath) {
    $keyPresent = [bool]([IO.File]::ReadAllLines($envPath) | Where-Object { $_ -match '^DASHSCOPE_API_KEY\s*=\s*\S+' } | Select-Object -First 1)
}
[PSCustomObject]@{
    Node = [bool](Get-Command node -ErrorAction SilentlyContinue)
    Python = (Test-Path -LiteralPath (Join-Path $root '.venv\Scripts\python.exe'))
    DevEcoStudio = @($paths)
    HarmonySdk = [bool]($hdcPath -and (Test-Path -LiteralPath $hdcPath))
    HdcOnPath = [bool](Get-Command hdc -ErrorAction SilentlyContinue)
    HdcAvailable = [bool]($hdcPath -and (Test-Path -LiteralPath $hdcPath))
    CloudKeyPresent = $keyPresent
} | ConvertTo-Json -Depth 3
