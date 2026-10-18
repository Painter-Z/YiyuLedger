"""Exercise Android 0.7.0 per-bill confirmation on the development emulator."""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PROFILES = {'standard': (393, 873, 1.0), 'narrow': (320, 720, 1.0),
            'large-font': (360, 800, 2.0), 'landscape': (873, 393, 1.0)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only the development emulator may be modified')
    command = [r'D:\Android\Sdk\platform-tools\adb.exe', '-s', args.serial]

    def adb(*parts, timeout=60, binary=False):
        result = subprocess.run(command + list(parts), capture_output=True, check=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode('utf-8', 'replace').strip()

    if (adb('shell', 'getprop', 'ro.boot.qemu.avd_name') != 'Yiyu_API36' or
            adb('shell', 'getprop', 'ro.boot.qemu') != '1'):
        raise RuntimeError('Expected the API36 Yiyu development emulator')
    apk = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    installed = adb('shell', 'pm', 'path', 'com.yiyu.ledger').removeprefix('package:')
    if adb('shell', 'sha256sum', installed).split()[0].lower() != sha:
        raise RuntimeError('Installed APK differs from the built APK')
    report = ROOT / 'artifacts/android-v070-recognition-ui'
    report.mkdir(exist_ok=True)
    original = {key: adb('shell', *parts) for key, parts in {
        'size': ('wm', 'size'), 'density': ('wm', 'density'),
        'font': ('settings', 'get', 'system', 'font_scale'),
        'rotation': ('settings', 'get', 'system', 'user_rotation'),
        'accelerometer': ('settings', 'get', 'system', 'accelerometer_rotation'),
        'fixed': ('cmd', 'window', 'fixed-to-user-rotation'),
    }.items()}
    rows = []
    captures = [f'recognition-v070-{name}.{suffix}' for name in ['first', 'second', 'cents'] for suffix in ['png', 'xml']]
    try:
        adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', 'enabled')
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        for name, (width, height, font) in PROFILES.items():
            print(f'{name}: {width}x{height}dp / font {font}', flush=True)
            adb('shell', 'am', 'force-stop', 'com.yiyu.ledger')
            adb('shell', 'wm', 'size', f'{width * 2}x{height * 2}')
            adb('shell', 'wm', 'density', '320')
            adb('shell', 'settings', 'put', 'system', 'font_scale', str(font))
            for capture in captures:
                adb('shell', 'run-as', 'com.yiyu.ledger', 'rm', '-f', 'files/' + capture)
            time.sleep(1)
            output = adb('shell', 'am', 'instrument', '-w', '-e', 'class', 'com.yiyu.ledger.RecognitionUiTest',
                         'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
            (report / f'{name}.log').write_text(output, encoding='utf-8')
            folder = report / name
            folder.mkdir(exist_ok=True)
            for capture in captures:
                data = adb('exec-out', 'run-as', 'com.yiyu.ledger', 'cat', 'files/' + capture, binary=True)
                (folder / capture).write_bytes(data)
            dimensions = all(struct.unpack('>II', (folder / capture).read_bytes()[16:24]) == (width * 2, height * 2)
                             for capture in captures if capture.endswith('.png'))
            passed = 'OK (2 tests)' in output and 'FAILURES' not in output and dimensions
            rows.append(dict(profile=name, width_dp=width, height_dp=height, font_scale=font, tests=2,
                             screenshots=3, passed=passed, dimensions_verified=dimensions))
            (report / 'results.json').write_text(json.dumps(dict(version='0.7.0', api=36, apk_sha256=sha,
                tested_at=datetime.now(timezone.utc).isoformat(), profiles=rows), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print('  PASS' if passed else '  FAIL', flush=True)
            if not passed:
                raise RuntimeError('Confirmation layout failed; inspect the saved emulator report')
    finally:
        for name in ['size', 'density']:
            override = re.search(r'Override (?:size|density):\s*(\S+)', original[name])
            adb('shell', 'wm', name, override.group(1) if override else 'reset')
        for name, key in [('font', 'font_scale'), ('rotation', 'user_rotation'), ('accelerometer', 'accelerometer_rotation')]:
            if original[name] == 'null': adb('shell', 'settings', 'delete', 'system', key)
            else: adb('shell', 'settings', 'put', 'system', key, original[name])
        fixed = re.search(r'(default|enabled|disabled)', original['fixed'])
        if fixed: adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', fixed.group(1))
        adb('shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher')

if __name__ == '__main__':
    main()
