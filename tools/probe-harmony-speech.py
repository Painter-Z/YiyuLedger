"""Opt-in ASR check for the two generated synthetic WAV files; never saves ledger entries."""
import argparse
import asyncio
import json
import sys
import wave
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
parser = argparse.ArgumentParser()
parser.add_argument('--live', action='store_true')
args = parser.parse_args()
if not args.live:
    raise SystemExit('Use --live after generating the two synthetic 16kHz mono WAV files. No personal recording is read.')

from backend.config import Settings, get_settings
from backend.providers import CloudProvider, transcribe_pcm


async def main():
    configured = get_settings()
    if not configured.api_key:
        raise SystemExit('Configure the existing backend/.env key without putting it in chat or argv.')
    # Match the Harmony app's existing model, endpoint and PCM format.
    provider = CloudProvider(Settings(api_key=configured.api_key, asr_model='fun-asr-realtime'))
    samples = [
        ('multiple', [['保障餐', 800, 'expense'], ['粥', 250, 'expense']]),
        ('cents', [['牛肉面', 1189, 'expense']]),
    ]
    results = []
    for name, expected in samples:
        with wave.open(str(root / f'artifacts/harmony-v070-speech-{name}.wav'), 'rb') as audio:
            assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (16000, 1, 2)
            pcm = audio.readframes(audio.getnframes())
            assert 6400 <= len(pcm) <= 960000
        try:
            transcript = await asyncio.wait_for(transcribe_pcm(provider, pcm), 90)
            assert transcript
            results.append({'name': 'synthetic-speech-' + name, 'transcript': transcript, 'expected': expected})
            print('ASR ' + name + ': ' + transcript, flush=True)
        except Exception:
            raise SystemExit('Synthetic ASR verification failed; no provider exception or credential was logged.') from None
    (root / 'artifacts/harmony-v070-speech-probe.json').write_text(
        json.dumps({'synthetic_only': True, 'model': 'fun-asr-realtime', 'cases': results}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


asyncio.run(main())
