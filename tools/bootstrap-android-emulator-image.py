"""Resume and verify official Android phone images on D:.

Only used when SDK Manager's large download stalls. Metadata and checksums are
read from Google's SDK catalogue, not invented or obtained from a mirror.
"""
from pathlib import Path
import argparse
import copy
import concurrent.futures
import hashlib
import re
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

root = Path(r'D:\Android')
arguments = argparse.ArgumentParser()
arguments.add_argument('--api', type=int, choices=(35, 36, 37), default=37)
api = arguments.parse_args().api
platform = '37.0' if api == 37 else str(api)
label = f'Android {api - 20}'
package_id = f'system-images;android-{platform};google_apis;x86_64'
catalogue_url = 'https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml'
raw = urllib.request.urlopen(catalogue_url, timeout=45).read()
catalogue = ET.fromstring(raw)
package = next(p for p in catalogue.findall('remotePackage') if p.attrib.get('path') == package_id)
complete = package.find('./archives/archive/complete')
name = complete.findtext('url')
expected_size = int(complete.findtext('size'))
expected_hash = complete.findtext('checksum')
hash_type = complete.find('checksum').attrib['type']
url = catalogue_url.rsplit('/', 1)[0] + '/' + name
archive = root / 'Downloads' / name
archive.parent.mkdir(parents=True, exist_ok=True)
old = root / 'Sdk/.temp/PackageOperation01' / name
if not archive.exists() and old.exists():
    if not old.resolve().is_relative_to(root.resolve()):
        raise RuntimeError('Invalid SDK cache path')
    old.replace(archive)

def digest(path):
    checksum = hashlib.new(hash_type)
    with path.open('rb') as stream:
        while data := stream.read(4 * 1024 * 1024):
            checksum.update(data)
    return checksum.hexdigest()

def ranged_part(start, end):
    part = archive.with_name(f'{name}.{start}-{end}.part')
    for attempt in range(12):
        offset = part.stat().st_size if part.exists() else 0
        if offset == end - start + 1:
            return part
        try:
            request = urllib.request.Request(url, headers={'Range': f'bytes={start + offset}-{end}'})
            with urllib.request.urlopen(request, timeout=25) as response:
                if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {start + offset}-'):
                    raise RuntimeError('Server did not honour range')
                with part.open('ab') as stream:
                    while data := response.read(256 * 1024):
                        stream.write(data)
            if part.stat().st_size != end - start + 1:
                raise RuntimeError('Incomplete range')
            print(f'Image range {start // 1024 // 1024}-{end // 1024 // 1024} MiB complete', flush=True)
            return part
        except Exception as error:
            if attempt == 11:
                raise
            print(f'Image range retry: {type(error).__name__}', flush=True)
            time.sleep(1)

position = archive.stat().st_size if archive.exists() else 0
if 0 <= position < expected_size:
    ranges = [(start, min(start + 64 * 1024 * 1024 - 1, expected_size - 1)) for start in range(position, expected_size, 64 * 1024 * 1024)]
    print(f'Resuming {len(ranges)} verified byte ranges using four connections', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        parts = list(pool.map(lambda bounds: ranged_part(*bounds), ranges))
    with archive.open('ab') as destination:
        for part in parts:
            with part.open('rb') as source:
                while data := source.read(4 * 1024 * 1024):
                    destination.write(data)
            part.unlink()

for attempt in range(12):
    if archive.exists() and archive.stat().st_size == expected_size:
        if digest(archive) != expected_hash:
            raise RuntimeError('Official image checksum mismatch; preserving archive for diagnosis')
        break
    position = archive.stat().st_size if archive.exists() else 0
    try:
        request = urllib.request.Request(url, headers={'Range': f'bytes={position}-'} if position else {})
        with urllib.request.urlopen(request, timeout=30) as response:
            resume = position > 0 and response.status == 206
            if resume:
                if not response.headers.get('Content-Range', '').startswith(f'bytes {position}-'):
                    raise RuntimeError('Incorrect download range')
            else:
                position = 0
            marker = position
            with archive.open('ab' if resume else 'wb') as stream:
                while data := response.read(256 * 1024):
                    stream.write(data)
                    position += len(data)
                    if position - marker >= 50 * 1024 * 1024:
                        print(f'{label} image: {position // 1024 // 1024}/{expected_size // 1024 // 1024} MiB', flush=True)
                        marker = position
        if position != expected_size or digest(archive) != expected_hash:
            raise RuntimeError('Incomplete image archive')
        break
    except Exception as error:
        if attempt == 11:
            raise
        print(f'Image transfer interrupted ({type(error).__name__}); resuming {attempt + 2}/12', flush=True)
        time.sleep(2)

assert archive.stat().st_size == expected_size and digest(archive) == expected_hash
print(f'{label} image official catalogue checksum verified; extracting', flush=True)
target = root / f'Sdk/system-images/android-{platform}/google_apis/x86_64'
target.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as contents:
    for member in contents.infolist():
        relative = Path(member.filename).relative_to('x86_64')
        destination = target / relative
        if not destination.resolve().is_relative_to(target.resolve()):
            raise RuntimeError('Invalid image archive path')
        if member.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with contents.open(member) as source, destination.open('wb') as output:
                while data := source.read(4 * 1024 * 1024):
                    output.write(data)

# SDK localPackage uses the exact official type, revision and licence metadata.
namespace = re.search(rb'xmlns:sys-img="([^"]+)"', raw).group(1).decode()
ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
ET.register_namespace('common', 'http://schemas.android.com/repository/android/common/02')
repository = ET.Element('{http://schemas.android.com/repository/android/common/02}repository', {'xmlns:sys-img': namespace})
license_id = package.find('uses-license').attrib['ref']
repository.append(copy.deepcopy(next(p for p in catalogue.findall('license') if p.attrib['id'] == license_id)))
local = copy.deepcopy(package)
local.tag = 'localPackage'
for child in list(local):
    if child.tag in {'archives', 'dependencies', 'channelRef'}:
        local.remove(child)
repository.append(local)
ET.ElementTree(repository).write(target / 'package.xml', encoding='utf-8', xml_declaration=True)
print(f'{label} emulator image ready: {target}', flush=True)
