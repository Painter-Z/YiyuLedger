"""Download the official, SHA-256 verified Gradle distribution onto D:."""
from pathlib import Path
import hashlib
import time
import urllib.request
import zipfile

root = Path(r'D:\Android')
version = '9.6.0'
name = f'gradle-{version}-bin.zip'
base = 'https://downloads.gradle.org/distributions/'
archive = root / 'Downloads' / name
archive.parent.mkdir(parents=True, exist_ok=True)
expected = urllib.request.urlopen(base + name + '.sha256', timeout=60).read().decode().strip().split()[0]
if len(expected) != 64:
    raise RuntimeError('Invalid official checksum')
for attempt in range(8):
    if archive.exists() and hashlib.sha256(archive.read_bytes()).hexdigest() == expected:
        break
    position = archive.stat().st_size if archive.exists() else 0
    try:
        request = urllib.request.Request(base + name, headers={'Range': f'bytes={position}-'} if position else {})
        with urllib.request.urlopen(request, timeout=75) as response:
            resume = position > 0 and response.status == 206
            if not resume:
                position = 0
            marker = position
            with archive.open('ab' if resume else 'wb') as destination:
                while chunk := response.read(1024 * 1024):
                    destination.write(chunk)
                    position += len(chunk)
                    if position - marker >= 20 * 1024 * 1024:
                        print(f'Gradle: {position // 1024 // 1024} MiB', flush=True)
                        marker = position
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Checksum mismatch')
        break
    except Exception as error:
        if attempt == 7:
            raise
        print(f'Gradle download interrupted ({type(error).__name__}), retry {attempt + 2}/8', flush=True)
        time.sleep(2)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
target = root / 'Tools'
target.mkdir(exist_ok=True)
with zipfile.ZipFile(archive) as contents:
    for member in contents.infolist():
        destination = target / member.filename
        if not destination.resolve().is_relative_to(target.resolve()):
            raise RuntimeError('Invalid archive path')
    contents.extractall(target)
(root / 'Downloads' / (name + '.sha256')).write_text(expected + '\n', encoding='ascii')
print(f'Gradle {version}: official SHA-256 verified; ready in {target}', flush=True)
