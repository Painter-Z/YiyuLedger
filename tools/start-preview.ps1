$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
$nodePath = if ($nodeCommand) { $nodeCommand.Source } else { Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' }
if (-not (Test-Path -LiteralPath $nodePath)) { throw '请安装 Node.js 24 或以上版本。' }
Push-Location -LiteralPath $projectRoot
try { & $nodePath (Join-Path $projectRoot 'tools\preview-server.mjs') }
finally { Pop-Location }
