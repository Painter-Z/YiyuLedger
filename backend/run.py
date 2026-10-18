"""Run locally by default; LAN use requires an explicit --lan and a connection token."""
import argparse
import os
from pathlib import Path
import secrets
import socket
import uvicorn
from .config import get_settings


def main():
    parser = argparse.ArgumentParser(description='一语账识别服务')
    parser.add_argument('--lan', action='store_true', help='供同一可信局域网内的手机连接')
    args = parser.parse_args()
    settings = get_settings()
    if not settings.api_token:
        token_file = Path(__file__).with_name('.access-token')
        if not token_file.exists():
            token_file.write_text(secrets.token_urlsafe(24), encoding='utf-8')
        os.environ['LEDGER_API_TOKEN'] = token_file.read_text(encoding='utf-8').strip()
    else:
        os.environ['LEDGER_API_TOKEN'] = settings.api_token
    host = '0.0.0.0' if args.lan else '127.0.0.1'
    os.environ['HOST'] = host
    # The connection token is displayed only in this user-run terminal, never in build/test logs.
    print('一语账识别服务 · 不保存录音或账本')
    print('百炼 API Key：' + ('已配置' if settings.api_key else '未配置；本地手动记账仍可使用'))
    if args.lan:
        addresses = sorted({info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
                            if not info[4][0].startswith('127.')})
        for address in addresses:
            print(f'手机服务地址：http://{address}:{settings.port}')
        print('仅在可信家庭网络使用 HTTP 调试连接；公开部署请配置 HTTPS。')
    else:
        print(f'本机地址：http://127.0.0.1:{settings.port}')
    print('手机连接口令：' + os.environ['LEDGER_API_TOKEN'], flush=True)
    uvicorn.run('backend.main:app', host=host, port=settings.port, access_log=False,
                ws_max_size=65536, timeout_keep_alive=10, log_level='warning')


if __name__ == '__main__':
    main()
