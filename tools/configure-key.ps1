param()
$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'backend\.env'
Write-Host '请使用阿里云百炼（北京地域）的新 API Key。输入会隐藏。请勿把密钥发送到聊天。'
Write-Host '获取说明：https://help.aliyun.com/zh/model-studio/get-api-key'
$secureKey = Read-Host 'DASHSCOPE_API_KEY' -AsSecureString
$plainKey = [System.Net.NetworkCredential]::new('', $secureKey).Password
try {
    if ([string]::IsNullOrWhiteSpace($plainKey) -or $plainKey.Contains("`n") -or $plainKey.Contains("`r")) {
        throw 'API Key 不能为空或包含换行。'
    }
    $lines = if (Test-Path -LiteralPath $envPath) { [IO.File]::ReadAllLines($envPath) } else { @() }
    $kept = @($lines | Where-Object { $_ -notmatch '^\s*DASHSCOPE_API_KEY\s*=' })
    [IO.File]::WriteAllLines($envPath, @($kept) + ('DASHSCOPE_API_KEY=' + $plainKey), [Text.UTF8Encoding]::new($false))
    Write-Host '已保存至 backend/.env，密钥内容未显示。重启识别服务后生效。'
} finally {
    $plainKey = $null
    $secureKey.Dispose()
}
