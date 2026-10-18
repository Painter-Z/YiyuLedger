"""Install pinned, checksum-verified Android command-line tools on D:.

No credentials or user ledger data are accessed. SDK packages are installed
separately with sdkmanager so its package catalogue remains authoritative.
"""
from pathlib import Path
import hashlib
import time
import urllib.request
import zipfile

ROOT = Path(r'D:\Android')
NAME = 'commandlinetools-win-15859902_latest.zip'
URL = 'https://dl.google.com/android/repository/' + NAME
SHA256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
archive_path = ROOT / 'Downloads' / NAME
target = ROOT / 'Sdk' / 'cmdline-tools' / 'latest'
archive_path.parent.mkdir(parents=True, exist_ok=True)

for attempt in range(6):
    if archive_path.exists() and hashlib.sha256(archive_path.read_bytes()).hexdigest() == SHA256:
        break
    position = archive_path.stat().st_size if archive_path.exists() else 0
    try:
        request = urllib.request.Request(URL, headers={'Range': f'bytes={position}-'} if position else {})
        with urllib.request.urlopen(request, timeout=75) as response:
            resume = position > 0 and response.status == 206
            if not resume:
                position = 0
            total = position + int(response.headers.get('Content-Length', '0'))
            marker = position
            with archive_path.open('ab' if resume else 'wb') as destination:
                while chunk := response.read(1024 * 1024):
                    destination.write(chunk)
                    position += len(chunk)
                    if position - marker >= 20 * 1024 * 1024:
                        print(f'Android tools: {position // 1024 // 1024}/{total // 1024 // 1024} MiB', flush=True)
                        marker = position
        if hashlib.sha256(archive_path.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError('Downloaded tools do not match the official SHA-256')
        break
    except Exception as error:
        if attempt == 5:
            raise
        print(f'Download interrupted ({type(error).__name__}); resuming attempt {attempt + 2}/6', flush=True)
        time.sleep(2)
else:
    raise RuntimeError('Android tools download incomplete')

assert hashlib.sha256(archive_path.read_bytes()).hexdigest() == SHA256
print('Android tools official SHA-256 verified', flush=True)
target.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive_path) as archive:
    for member in archive.infolist():
        relative = Path(member.filename).relative_to('cmdline-tools')
        destination = target / relative
        if not destination.resolve().is_relative_to(target.resolve()):
            raise RuntimeError('Archive path outside SDK directory')
        if member.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(member))
print(f'Android tools ready: {target}', flush=True)
