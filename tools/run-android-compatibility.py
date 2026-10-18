"""Run the UI matrix on a disposable Yiyu emulator, never on a physical phone."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = Path('D:/Android/Sdk/platform-tools/adb.exe')
PROFILES = {
    'standard': (393, 873, 1.0, 0, 'gestural', False),
    'narrow': (320, 720, 1.0, 0, 'gestural', False),
    'wide': (440, 956, 1.0, 0, 'gestural', False),
    'large_font': (360, 800, 2.0, 0, 'gestural', False),
    'display_zoom': (320, 640, 1.3, 0, 'gestural', False),
    'landscape': (393, 873, 1.0, 1, 'gestural', False),
    'landscape_font': (393, 873, 1.5, 1, 'gestural', True),
    'three_button_cutout': (393, 873, 1.3, 0, 'threebutton', True),
    'dark_mode': (393, 873, 1.3, 0, 'gestural', False),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--api', required=True, type=int, choices=[35, 36])
    parser.add_argument('--profiles', default=','.join(PROFILES))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only development emulators are permitted')
    selected = args.profiles.split(',')
    if any(name not in PROFILES for name in selected):
        parser.error('Unknown screen profile')

    def adb(*command, timeout=60):
        result = subprocess.run([str(ADB), '-s', args.serial, *command], capture_output=True, timeout=timeout)
        result.check_returncode()
        try:
            return result.stdout.decode('utf-8').strip()
        except UnicodeDecodeError:
            return result.stdout.decode('gb18030', errors='replace').strip()

    if adb('shell', 'getprop', 'ro.build.version.sdk') != str(args.api):
        raise RuntimeError('Emulator API does not match the requested test system')
    if not adb('emu', 'avd', 'name').startswith(f'Yiyu_API{args.api}'):
        raise RuntimeError('Only disposable Yiyu development AVDs are permitted')
    report_dir = ROOT / 'artifacts' / 'android-compatibility' / f'api{args.api}'
    report_dir.mkdir(parents=True, exist_ok=True)
    installed_path = adb('shell', 'pm', 'path', 'com.yiyu.ledger').removeprefix('package:')
    apk_hash = adb('shell', 'sha256sum', installed_path).split()[0].lower()
    local_hash = hashlib.sha256((ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
    if apk_hash != local_hash:
        raise RuntimeError('Installed app does not match the current APK; install the build before running this matrix')
    results_file = report_dir / 'results.json'
    previous = json.loads(results_file.read_text(encoding='utf-8')) if results_file.exists() else {}
    accumulated = {row['profile']: row for row in previous.get('profiles', [])} if previous.get('apk_sha256') == apk_hash else {}
    def check_geometry(name, width, height, rotation):
        expected = (height * 2, width * 2) if rotation else (width * 2, height * 2)
        sizes = {file.name: struct.unpack_from('>II', file.read_bytes(), 16)
            for file in (report_dir / name).glob('*.png') if file.name not in ('failure.png', 'persistence-failure.png')}
        valid = len(sizes) == 10 and all(size == expected for size in sizes.values())
        return valid, expected, sizes
    for row in accumulated.values():
        valid, expected, _ = check_geometry(row['profile'], row['width_dp'], row['height_dp'], row['rotation'])
        row.update(passed=row['passed'] and valid, screenshot_dimensions_verified=valid, screenshot_pixels=expected)
    settings = [('system', 'font_scale'), ('system', 'accelerometer_rotation'), ('system', 'user_rotation'),
                ('global', 'window_animation_scale'), ('global', 'transition_animation_scale'), ('global', 'animator_duration_scale')]
    old_settings = {(scope, key): adb('shell', 'settings', 'get', scope, key) for scope, key in settings}
    old_size = adb('shell', 'wm', 'size')
    old_density = adb('shell', 'wm', 'density')
    old_fixed_rotation = adb('shell', 'cmd', 'window', 'fixed-to-user-rotation')
    old_night = re.search(r'\b(auto|yes|no|custom)\b', adb('shell', 'cmd', 'uimode', 'night')).group(1)
    overlays = adb('shell', 'cmd', 'overlay', 'list')
    active_overlays = re.findall(r'^\[x\]\s+(\S+)', overlays, re.MULTILINE)
    results = []
    try:
        # The emulator launcher can request portrait. Force the display itself
        # to follow the test rotation rather than silently accepting a setting.
        adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', 'enabled')
        for scope, key in settings[3:]:
            adb('shell', 'settings', 'put', scope, key, '0')
        for name in selected:
            width, height, font, rotation, navigation, cutout = PROFILES[name]
            print(f'Android {args.api - 20} / {name}: {width}x{height} dp, font {font}, rotation {rotation}', flush=True)
            adb('shell', 'am', 'force-stop', 'com.yiyu.ledger')
            adb('shell', 'cmd', 'uimode', 'night', 'yes' if name == 'dark_mode' else 'no')
            adb('shell', 'wm', 'size', f'{width * 2}x{height * 2}')
            adb('shell', 'wm', 'density', '320')
            adb('shell', 'settings', 'put', 'system', 'font_scale', str(font))
            adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
            adb('shell', 'settings', 'put', 'system', 'user_rotation', str(rotation))
            adb('shell', 'cmd', 'window', 'user-rotation', 'lock', str(rotation))
            nav = f'com.android.internal.systemui.navbar.{navigation}'
            if nav not in overlays:
                raise RuntimeError(f'Required navigation overlay unavailable: {nav}')
            adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', nav)
            cutout_overlays = re.findall(r'\[[ x]\]\s+(com\.android\.internal\.display\.cutout\.emulation\.\S+)', overlays)
            for overlay in cutout_overlays:
                adb('shell', 'cmd', 'overlay', 'disable', overlay)
            if cutout:
                tall = 'com.android.internal.display.cutout.emulation.tall'
                if tall not in overlays:
                    raise RuntimeError('Required cutout overlay unavailable')
                adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', tall)
            time.sleep(2)
            output = adb('shell', 'am', 'instrument', '-w', '-e', 'class', 'com.yiyu.ledger.CompatibilityUiTest',
                         '-e', 'profile', name, 'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner', timeout=300)
            (report_dir / f'{name}.log').write_text(output + '\n', encoding='utf-8')
            # All captured pictures and hierarchies contain synthetic test data only.
            archive = report_dir / f'{name}.tar'
            archive.write_bytes(subprocess.check_output([str(ADB), '-s', args.serial, 'exec-out', 'run-as',
                'com.yiyu.ledger', 'tar', '-cf', '-', '-C', f'files/compatibility/{name}', '.']))
            import tarfile
            picture_dir = report_dir / name
            if picture_dir.exists():
                if not picture_dir.resolve().is_relative_to(report_dir.resolve()):
                    raise RuntimeError('Screenshot output escaped the test report directory')
                for file in picture_dir.iterdir():
                    if file.is_file() and file.suffix in ('.png', '.xml', '.txt'):
                        file.unlink()
            with tarfile.open(archive) as screenshots:
                screenshots.extractall(picture_dir, filter='data')
            archive.unlink()
            passed = bool(re.search(r'OK \(1 test\)', output)) and 'FAILURES' not in output
            dimensions_valid, expected_pixels, picture_sizes = check_geometry(name, width, height, rotation)
            passed = passed and dimensions_valid
            result = dict(profile=name, width_dp=width, height_dp=height, font_scale=font, rotation=rotation,
                          navigation=navigation, simulated_cutout=cutout, dark_mode=name == 'dark_mode', passed=passed,
                          screenshot_dimensions_verified=dimensions_valid, screenshot_pixels=expected_pixels,
                          tested_at=datetime.now(timezone.utc).isoformat())
            results.append(result)
            accumulated[name] = result
            results_file.write_text(json.dumps({'api': args.api, 'apk_sha256': apk_hash,
                'profiles': [accumulated[key] for key in PROFILES if key in accumulated]}, indent=2), encoding='utf-8')
            print(f'{name}: {"PASS" if passed else "FAIL"}', flush=True)
            if not passed:
                print(output[-6000:], flush=True)
                if not dimensions_valid:
                    print(f'Screenshot geometry mismatch: expected {expected_pixels}; got {picture_sizes}', flush=True)
                break
    finally:
        old_rotation = old_settings[('system', 'user_rotation')]
        old_rotation_mode = 'free' if old_settings[('system', 'accelerometer_rotation')] == '1' else 'lock'
        adb('shell', 'cmd', 'window', 'user-rotation', old_rotation_mode, old_rotation if old_rotation != 'null' else '0')
        adb('shell', 'cmd', 'window', 'fixed-to-user-rotation', old_fixed_rotation)
        for (scope, key), value in old_settings.items():
            if value == 'null':
                adb('shell', 'settings', 'delete', scope, key)
            else:
                adb('shell', 'settings', 'put', scope, key, value)
        size_match = re.search(r'Override size: (\d+x\d+)', old_size)
        density_match = re.search(r'Override density: (\d+)', old_density)
        adb('shell', 'wm', 'size', size_match.group(1) if size_match else 'reset')
        adb('shell', 'wm', 'density', density_match.group(1) if density_match else 'reset')
        adb('shell', 'cmd', 'uimode', 'night', old_night)
        for overlay in active_overlays:
            if '.navbar.' in overlay or '.cutout.emulation.' in overlay:
                adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', overlay)
        for overlay in re.findall(r'\[[ x]\]\s+(com\.android\.internal\.display\.cutout\.emulation\.\S+)', overlays):
            if overlay not in active_overlays:
                adb('shell', 'cmd', 'overlay', 'disable', overlay)
    if len(results) != len(selected) or not all(row['passed'] for row in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
