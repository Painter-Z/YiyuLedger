"""Check Harmony browsing parity and centered dialogs on disposable Android emulators."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import time
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ADB = Path('D:/Android/Sdk/platform-tools/adb.exe')
PROFILES = {'standard': (393, 873), 'narrow': (320, 720), 'landscape': (873, 393)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, required=True, choices=[35, 36, 37])
    parser.add_argument('--profiles', default=','.join(PROFILES))
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    profiles = args.profiles.split(',')
    if any(p not in PROFILES for p in profiles): parser.error('Unknown profile')
    command = [str(ADB), '-s', args.serial]

    def adb(*parts, timeout=60, binary=False):
        result = subprocess.run(command + list(parts), capture_output=True, timeout=timeout, check=True)
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()

    if not re.fullmatch(r'emulator-\d+', args.serial) or adb('emu', 'avd', 'name').splitlines()[0] != f'Yiyu_API{args.api}':
        raise RuntimeError('Only the matching Yiyu development emulator may be modified')
    if adb('shell', 'getprop', 'ro.build.version.sdk') != str(args.api): raise RuntimeError('Unexpected Android API')
    apk = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    version = json.loads((apk.parent / 'output-metadata.json').read_text(encoding='utf-8'))['elements'][0]['versionName']
    report = ROOT / f'artifacts/android-{version}-browse-ui/api{args.api}'
    report.mkdir(parents=True, exist_ok=True)
    adb('install', '-r', str(apk))
    adb('install', '-r', str(ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    installed = adb('shell', 'pm', 'path', 'com.yiyu.ledger').removeprefix('package:')
    if adb('shell', 'sha256sum', installed).split()[0].lower() != sha: raise RuntimeError('Installed APK differs')
    original = {key: adb('shell', *parts) for key, parts in {
        'size': ('wm', 'size'), 'density': ('wm', 'density'), 'font': ('settings', 'get', 'system', 'font_scale'),
        'rotation': ('settings', 'get', 'system', 'user_rotation'), 'accelerometer': ('settings', 'get', 'system', 'accelerometer_rotation'),
        'fixed': ('cmd', 'window', 'fixed-to-user-rotation'),
    }.items()}
    prior = report / 'results.json'
    recorded = {}
    if prior.exists():
        previous = json.loads(prior.read_text(encoding='utf-8'))
        if previous.get('api') == args.api and previous.get('apk_sha256') == sha:
            recorded = {p['profile']: p for p in previous['profiles']}
    try:
        adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', 'enabled')
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        for profile in profiles:
            width, height = PROFILES[profile]
            print(f'API {args.api}, {profile}: {width}x{height}dp, default font', flush=True)
            adb('shell', 'am', 'force-stop', 'com.yiyu.ledger')
            adb('shell', 'wm', 'size', f'{width*2}x{height*2}')
            adb('shell', 'wm', 'density', '320')
            adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
            time.sleep(1)
            test = 'com.yiyu.ledger.LedgerBrowseUiTest'
            if profile != 'standard': test += '#calendarSearchAndReturnMatchHarmony'
            output = adb('shell', 'am', 'instrument', '-w', '-e', 'class', test, '-e', 'profile', profile,
                         'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner', timeout=300)
            (report / f'{profile}.log').write_text(output, encoding='utf-8')
            folder = report / profile
            folder.mkdir(exist_ok=True)
            for old in folder.iterdir():
                if old.is_file() and old.suffix in ('.png', '.xml'): old.unlink()
            archive = adb('exec-out', 'run-as', 'com.yiyu.ledger', 'tar', '-cf', '-', '-C', 'files/ledger-browse-ui', '.', binary=True)
            with tarfile.open(fileobj=io.BytesIO(archive)) as captures: captures.extractall(folder, filter='data')
            expected = {'recent-days', 'centered-picker', 'picker-cross-year', 'picker-month-boundary', 'selected-day-return', 'history-search-return',
                        'picker-leap-day', 'empty-day-return', 'deleted-daily-total', 'statistics-return'}
            if profile == 'standard': expected |= {'paged-all-results-return', 'rotated-centered-picker', 'reserved-row-keys', 'picker-before-rotation', 'picker-reopened'}
            images = {p.stem for p in folder.glob('*.png')}
            dimensions = all(Image.open(folder/f'{name}.png').size == (width*2, height*2) for name in expected - {'rotated-centered-picker'} if name in images)
            count = 3 if profile == 'standard' else 1
            passed = f'OK ({count} test' in output and 'FAILURES' not in output and expected <= images and dimensions
            recorded[profile] = dict(profile=profile, width_dp=width, height_dp=height, font_scale=1.0, passed=passed,
                tests=count, captures=len(expected), screenshot_dimensions_verified=dimensions, tested_at=datetime.now(timezone.utc).isoformat())
            prior.write_text(json.dumps({'version':version,'api':args.api,'apk_sha256':sha,
                'profiles':[recorded[p] for p in PROFILES if p in recorded]}, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f'  {"PASS" if passed else "FAIL"}, {count} tests, {len(expected)} captures: {report/profile}', flush=True)
            if not passed: raise RuntimeError(output[-7000:])
    finally:
        for name in ['size', 'density']:
            override = re.search(r'Override (?:size|density):\s*(\S+)', original[name])
            adb('shell', 'wm', name, override.group(1) if override else 'reset')
        for name, key in [('font', 'font_scale'), ('rotation', 'user_rotation'), ('accelerometer', 'accelerometer_rotation')]:
            adb('shell', 'settings', 'put', 'system', key, original[name])
        match = re.search(r'(default|enabled|disabled)', original['fixed'])
        if match: adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', match.group(1))
        # Quickstep can retain a gesture region from the previous overridden
        # screen size and steal later swipes. Refresh it after restoring the AVD.
        adb('shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher')


if __name__ == '__main__': main()
