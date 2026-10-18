param(
    [string]$StudioPath = 'D:\DevEcoStudio\DevEco Studio',
    [string]$DeviceId = '',
    [ValidateRange(1024, 65535)][int]$ServicePort = 8787
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$hdc = Join-Path $StudioPath 'sdk\default\openharmony\toolchains\hdc.exe'
$venvPython = Join-Path $projectRoot '.venv\Scripts\python.exe'
foreach ($file in @($hdc, $venvPython)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "未找到所需工具：$file" }
}
$devices = @(& $hdc list targets | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch '^\[' })
if (-not $DeviceId) {
    if ($devices.Count -eq 0) { throw '请连接手机 USB，开启 USB 调试，并在手机上允许这台电脑调试。' }
    if ($devices.Count -ne 1) { throw '检测到多台设备，请通过 -DeviceId 指定本次使用的手机。' }
    $DeviceId = $devices[0]
} elseif ($DeviceId -notin $devices) { throw '指定的手机尚未连接或未允许 USB 调试。' }

$forwardTasks = @(& $hdc -t $DeviceId fport ls)
$taskPattern = '^' + [regex]::Escape($DeviceId) + '\s+tcp:' + $ServicePort + '\s+tcp:' + $ServicePort + '\s+\[Reverse\]'
if (-not ($forwardTasks -match $taskPattern)) {
    $routeResult = & $hdc -t $DeviceId rport "tcp:$ServicePort" "tcp:$ServicePort"
    if ($LASTEXITCODE -ne 0 -or ($routeResult -match '\[Fail\]')) { throw 'USB 服务通道建立失败，请检查 USB 调试状态。' }
}
Write-Host "USB 通道已就绪，手机服务地址：http://127.0.0.1:$ServicePort"
$env:PYTHONIOENCODING = 'utf-8'
$env:PORT = [string]$ServicePort
$healthProbe = @'
import json, urllib.request
from pathlib import Path
from backend.config import get_settings
try:
    settings = get_settings()
    token = settings.api_token or Path('backend/.access-token').read_text(encoding='utf-8').strip()
    request = urllib.request.Request(f'http://127.0.0.1:{settings.port}/health', headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(request, timeout=2) as response:
        health = json.load(response)
    print('ready' if health.get('status') == 'ok' and health.get('protocol_version') == 1 else 'not_ready')
except Exception:
    print('not_ready')
'@
Push-Location -LiteralPath $projectRoot
try {
    $serviceState = & $venvPython -c $healthProbe
    if ($serviceState -eq 'ready') {
        Write-Host '识别服务已经运行，可以直接在手机中按住说话。请保持 USB 连接和电脑运行。'
    } else {
        Write-Host '正在启动识别服务；使用期间请保持本窗口、USB 连接和电脑运行。'
        & $PSScriptRoot\start-backend.ps1
    }
} finally { Pop-Location }
