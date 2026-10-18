"""Opt-in cloud probe using only synthetic speech and an existing local .env key.

Run after installing the app and androidTest APK onto our emulator. The key is
passed through stdin into a short-lived app-private file and removed in finally;
it is never in argv, tool output, source, APK, exported backups, or screenshots.
"""
from pathlib import Path
import argparse
import re
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    raise SystemExit('This probe is restricted to the development emulator.')
adb = [r'D:\Android\Sdk\platform-tools\adb.exe', '-s', args.serial]
credential = re.search(r'^DASHSCOPE_API_KEY=(\S+)', (root / 'backend/.env').read_text(encoding='utf-8-sig'), re.M)
if not credential:
    raise SystemExit('Local credential missing; use tools/configure-key.ps1.')
audio = root / 'artifacts/android-synthetic-voice.wav'
if not audio.exists():
    raise SystemExit('Generate the synthetic Chinese speech fixture first.')
key = credential.group(1).strip('"\'')
def run(command, **kwargs):
    return subprocess.run(adb + command, check=True, capture_output=True, **kwargs)
try:
    run(['shell', '-T', 'run-as', 'com.yiyu.ledger', 'dd', 'of=files/cloud-probe-key'], input=key.encode())
    # Windows adb shell stdin can truncate binary audio; push the nonsensitive
    # synthetic WAV, then copy it into the app sandbox as the app UID.
    run(['push', str(audio), '/data/local/tmp/yiyu-cloud-probe.wav'])
    run(['shell', 'run-as', 'com.yiyu.ledger', 'cp', '/data/local/tmp/yiyu-cloud-probe.wav', 'files/cloud-probe.wav'])
    result = run(['shell', 'am', 'instrument', '-w', '-e', 'class', 'com.yiyu.ledger.CloudConnectivityTest', 'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner'])
    output = result.stdout.decode('utf-8', 'replace')
    # Display only the pass/fail summary, never arbitrary instrumentation output.
    if 'OK (1 test)' in output and 'FAILURES' not in output and 'INSTRUMENTATION_FAILED' not in output:
        (root / 'artifacts/android-cloud-probe-failed.txt').unlink(missing_ok=True)
        (root / 'artifacts/android-cloud-probe-result.txt').write_text('PASS: Android emulator HTTPS text extraction and WSS synthetic Chinese speech, expected 15.00 CNY / food; no bills saved.\n', encoding='utf-8')
        print('Android emulator: real HTTPS text extraction and WSS synthetic-speech recognition passed.')
    else:
        (root / 'artifacts/android-cloud-probe-failed.txt').write_text(output.replace(key, '[REDACTED]'), encoding='utf-8')
        raise SystemExit('Cloud probe failed; redacted diagnostics saved to artifacts/android-cloud-probe-failed.txt')
finally:
    for name in ['cloud-probe-key', 'cloud-probe.wav']:
        run(['shell', 'run-as', 'com.yiyu.ledger', 'rm', '-f', 'files/' + name])
    run(['shell', 'rm', '-f', '/data/local/tmp/yiyu-cloud-probe.wav'])
    key = None
