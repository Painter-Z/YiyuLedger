"""Install Google's archived stable emulator 36.6.11 separately on D:.

The official size and SHA-256 come from developer.android.com/studio/emulator_archive.
This runtime is used for API 35/36 tests without replacing the SDK's API 37 runtime.
"""
import concurrent.futures
import hashlib
from pathlib import Path
import shutil
import time
import urllib.request
import zipfile

NAME = 'emulator-windows_x64-15507667.zip'
SIZE = 418510366
CHECKSUM = '7dfb2c47291db7e0fd7ce45e541706d45827bc9875f5f736bf785585b549e0e4'
URL = 'https://dl.google.com/android/repository/' + NAME
ARCHIVE = Path('D:/Android/Downloads') / NAME
TARGET = Path('D:/Android/Tools/emulator-36.6.11')
ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
position = ARCHIVE.stat().st_size if ARCHIVE.exists() else 0


def part(bounds):
    start, end = bounds
    path = ARCHIVE.with_name(f'{NAME}.{start}-{end}.part')
    for attempt in range(10):
        offset = path.stat().st_size if path.exists() else 0
        if offset == end - start + 1:
            return path
        try:
            request = urllib.request.Request(URL, headers={'Range': f'bytes={start + offset}-{end}'})
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {start + offset}-'):
                    raise RuntimeError('Server did not honor the byte range')
                with path.open('ab') as stream:
                    while data := response.read(256 * 1024):
                        stream.write(data)
            if path.stat().st_size != end - start + 1:
                raise RuntimeError('Incomplete emulator range')
            print(f'Emulator range {start // 1024**2}-{end // 1024**2} MiB complete', flush=True)
            return path
        except Exception as error:
            if attempt == 9:
                raise
            print(f'Retrying emulator transfer: {type(error).__name__}', flush=True)
            time.sleep(1)


if position < SIZE:
    ranges = [(start, min(start + 32 * 1024**2 - 1, SIZE - 1)) for start in range(position, SIZE, 32 * 1024**2)]
    print(f'Resuming stable emulator from {position // 1024**2} MiB using four connections', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        parts = list(pool.map(part, ranges))
    with ARCHIVE.open('ab') as stream:
        for path in parts:
            with path.open('rb') as source:
                while data := source.read(4 * 1024**2):
                    stream.write(data)
            path.unlink()
hash_value = hashlib.sha256()
with ARCHIVE.open('rb') as stream:
    while data := stream.read(4 * 1024**2):
        hash_value.update(data)
if ARCHIVE.stat().st_size != SIZE or hash_value.hexdigest() != CHECKSUM:
    raise RuntimeError('Official emulator archive checksum mismatch')
TARGET.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(ARCHIVE) as archive:
    for info in archive.infolist():
        destination = (TARGET / info.filename).resolve()
        if not destination.is_relative_to(TARGET.resolve()):
            raise RuntimeError('Unsafe archive path')
    archive.extractall(TARGET)
# This separate runtime needs its DLL dependencies beside the direct QEMU entry
# point on this Windows installation. The SDK-managed emulator stays untouched.
runtime = TARGET / 'emulator'
qemu = runtime / 'qemu' / 'windows-x86_64'
for library in (runtime / 'lib64').glob('*.dll'):
    shutil.copy2(library, qemu / library.name)
for name in ('libEGL.dll', 'libGLESv2.dll'):
    shutil.copy2(runtime / 'lib64' / 'gles_swiftshader' / name, qemu / name)
print(f'Official stable emulator verified and ready: {TARGET}', flush=True)
