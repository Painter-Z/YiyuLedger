"""Opt-in Android 0.7.0 cloud regression: synthetic text/speech, emulator only.

The existing local key is sent via stdin to a short-lived app-private file. It
never appears in argv, source, APKs, screenshots, or exported probe results.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import wave

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    raise SystemExit('This probe is restricted to the development emulator.')
adb = [r'D:\Android\Sdk\platform-tools\adb.exe', '-s', args.serial]
values = re.findall(r'^DASHSCOPE_API_KEY=(\S+)', (root / 'backend/.env').read_text(encoding='utf-8-sig'), re.M)
if not values:
    raise SystemExit('Local credential missing; use tools/configure-key.ps1.')
key = values[-1].strip('"\'')
source_audio = {name: root / f'artifacts/harmony-v070-speech-{name}.wav' for name in ['multiple', 'cents']}
if any(not path.is_file() for path in source_audio.values()):
    raise SystemExit('Generate the synthetic 16kHz speech fixtures first.')
# Canonical 44-byte WAV headers match the app recorder/replayer exactly. The
# speech samples themselves remain the same; no microphone audio is captured.
audio = {}
for name, path in source_audio.items():
    with wave.open(str(path), 'rb') as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
            raise SystemExit('Synthetic fixture must be mono PCM16 at 16kHz.')
        frames = source.readframes(source.getnframes())
    destination = root / f'artifacts/android-v070-speech-{name}.wav'
    with wave.open(str(destination), 'wb') as target:
        target.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        target.writeframes(frames)
    audio[name] = destination

def run(command, **kwargs):
    return subprocess.run(adb + command, check=True, capture_output=True, **kwargs)

try:
    run(['shell', 'run-as', 'com.yiyu.ledger', 'rm', '-f', 'files/recognition-v070-cloud-results.json'])
    run(['shell', '-T', 'run-as', 'com.yiyu.ledger', 'dd', 'of=files/recognition-probe-key'], input=key.encode())
    for name, path in audio.items():
        temporary = f'/data/local/tmp/yiyu-recognition-probe-{name}.wav'
        run(['push', str(path), temporary])
        run(['shell', 'run-as', 'com.yiyu.ledger', 'cp', temporary, f'files/recognition-probe-{name}.wav'])
    result = run(['shell', 'am', 'instrument', '-w', '-e', 'class', 'com.yiyu.ledger.RecognitionCloudTest', 'com.yiyu.ledger.test/androidx.test.runner.AndroidJUnitRunner'])
    output = result.stdout.decode('utf-8', 'replace').replace(key, '[REDACTED]')
    data = run(['exec-out', 'run-as', 'com.yiyu.ledger', 'cat', 'files/recognition-v070-cloud-results.json'])
    redacted = data.stdout.decode('utf-8', 'replace').replace(key, '[REDACTED]')
    payload = json.loads(redacted)
    (root / 'artifacts/android-v070-cloud-probe.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    passed = sum(case.get('passed') is True for case in payload.get('cases', []))
    print(f'Android real client: {passed}/12 cloud cases passed (10 text, 2 synthetic speech); no bills saved.')
    if passed != 12 or 'OK (1 test)' not in output or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output:
        (root / 'artifacts/android-v070-cloud-failure.log').write_text(output, encoding='utf-8')
        raise SystemExit('Cloud regression failed; redacted synthetic results saved under artifacts.')
    (root / 'artifacts/android-v070-cloud-failure.log').unlink(missing_ok=True)
finally:
    for name in ['recognition-probe-key', 'recognition-probe-multiple.wav', 'recognition-probe-cents.wav']:
        subprocess.run(adb + ['shell', 'run-as', 'com.yiyu.ledger', 'rm', '-f', 'files/' + name], capture_output=True)
    for name in audio:
        subprocess.run(adb + ['shell', 'rm', '-f', f'/data/local/tmp/yiyu-recognition-probe-{name}.wav'], capture_output=True)
    key = None
