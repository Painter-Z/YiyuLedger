param([switch]$Lan)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$venvPython = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    throw '请先安装 Python 3.11+，运行 python -m venv .venv，再运行 .\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt。'
}
$env:PYTHONIOENCODING = 'utf-8'
Push-Location -LiteralPath $projectRoot
try {
    if ($Lan) { & $venvPython -m backend.run --lan }
    else { & $venvPython -m backend.run }
} finally { Pop-Location }
