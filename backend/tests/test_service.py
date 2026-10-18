import asyncio
import base64
from datetime import date
import io
import json
import wave
import pytest
from fastapi.testclient import TestClient
from backend.config import Settings
from backend.domain import Proposal, evidence_minor, normalize_result
from backend.main import create_app, decode_wav
from backend.providers import CloudProvider, ProviderError, SpeechSession


def proposal(**overrides):
    return {"kind": "expense", "title": "辣椒炒肉盖饭", "amount_text": "15元", "amount_yuan": "15.00",
            "category_id": "food", "occurred_on": "2026-10-10", "date_text": "", "time_hint": "中午", "note": "", **overrides}


def parse(p=None, text="我中午吃了辣椒炒肉盖饭花了15元"):
    return normalize_result({"entries": [p or proposal()]}, text, date(2026, 10, 10))


@pytest.mark.parametrize("text, cents", [("15元", 1500), ("十五元", 1500), ("15块5毛", 1550),
    ("十五元五角六分", 1556), ("两百零八元", 20800), ("一千二百三十四点五六元", 123456),
    ("一万二千元", 1200000), ("人民币12.34元", 1234), ("十元半", 1050)])
def test_independent_amount_evidence(text, cents):
    assert evidence_minor(text) == cents


@pytest.mark.parametrize("text", ["0元", "-15元", "15.555元", "大概十五到二十元", "15美元", "1e4元", "NaN", ""])
def test_ambiguous_amounts_rejected(text):
    with pytest.raises(ValueError):
        evidence_minor(text)


def test_example_exact_cents_and_category():
    draft = parse().drafts[0]
    assert (draft.title, draft.amount_minor, draft.category_id, draft.time_hint) == ("辣椒炒肉盖饭", 1500, "food", "中午")


def test_model_cannot_invent_money_or_disagree_with_evidence():
    assert parse(proposal(amount_yuan="1500")).drafts[0].amount_minor is None
    assert parse(proposal(amount_text="20元", amount_yuan="20")).drafts[0].amount_minor is None
    assert parse(proposal(amount_text=None, amount_yuan=None)).drafts[0].amount_minor is None


def test_correction_can_quote_final_amount():
    assert parse(proposal(amount_text="13元", amount_yuan="13"), "午餐15元，不对是13元").drafts[0].amount_minor == 1300


def test_scaled_numbers_colloquial_fractions_and_ambiguous_omissions():
    assert evidence_minor('1.2万元') == 1200000
    assert evidence_minor('十五块五') == 1550
    with pytest.raises(ValueError): evidence_minor('两千五元')
    with pytest.raises(ValueError): evidence_minor('一百二元')


def test_foreign_currency_cannot_be_silently_saved_as_cny():
    assert parse(proposal(amount_text='15', amount_yuan='15'), '午餐15美元').drafts[0].amount_minor is None
    assert parse(proposal(currency='other')).drafts[0].amount_minor is None


def test_relative_date_is_independent_of_model_answer():
    draft = parse(proposal(date_text="昨天", occurred_on="2025-01-01"), "昨天午餐15元").drafts[0]
    assert draft.occurred_on == "2026-10-09"
    draft = parse(proposal(occurred_on="2025-01-01")).drafts[0]
    assert draft.occurred_on == "2026-10-10" and draft.issues


def test_invalid_and_hallucinated_dates_do_not_roll_over():
    assert parse(proposal(date_text="2月30日", occurred_on="2026-02-30"), "2月30日午餐15元").drafts[0].occurred_on == "2026-10-10"


@pytest.mark.parametrize("kind, category", [("income", "other"), ("refund", "food"), ("transfer", "transfer"), ("debt", "debt")])
def test_directions_and_category_constraints(kind, category):
    draft = parse(proposal(kind=kind)).drafts[0]
    assert draft.kind == kind and draft.category_id == category


def test_multiple_records_and_non_bookkeeping_text():
    result = normalize_result({"entries": [proposal(), proposal(title="地铁", category_id="transport", amount_text="8元", amount_yuan="8")]},
                              "午餐15元，地铁8元", date(2026, 10, 10))
    assert [d.amount_minor for d in result.drafts] == [1500, 800]
    assert normalize_result({"entries": []}, "你好", date.today()).issues
    with pytest.raises(ValueError):
        normalize_result({"entries": [proposal()] * 21}, "15元", date.today())


def wav_bytes(frames=3200, rate=16000, channels=1):
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(channels); audio.setsampwidth(2); audio.setframerate(rate)
        audio.writeframes(b"\x00\x00" * frames * channels)
    return output.getvalue()


def test_wav_format_duration_and_corruption():
    assert len(decode_wav(base64.b64encode(wav_bytes()).decode())) == 6400
    for raw in [b"junk", wav_bytes(rate=48000), wav_bytes(channels=2), wav_bytes(frames=20), wav_bytes(frames=480001), wav_bytes()[:-2]]:
        with pytest.raises(Exception):
            decode_wav(base64.b64encode(raw).decode())


class FakeSpeech:
    def __init__(self):
        self.done = asyncio.Event(); self.preview = "午餐15元"; self.pcm = b""; self.closed = False
    async def __aenter__(self): return self
    async def __aexit__(self, *args): self.closed = True
    async def send(self, value): self.pcm += value
    async def finish(self): self.done.set()
    async def events(self):
        yield self.preview
        await self.done.wait()


class FakeProvider:
    def __init__(self): self.session = None
    async def parse(self, text, reference): return normalize_result({"entries": [proposal()]}, text, reference)
    def speech(self): self.session = FakeSpeech(); return self.session


def client(provider=None):
    return TestClient(create_app(Settings(api_token="test-only-token"), provider or FakeProvider()))


def test_http_auth_and_no_secret_health_fields():
    with client() as c:
        assert c.get("/health").status_code == 401
        response = c.get("/health", headers={"Authorization": "Bearer test-only-token"})
        assert response.status_code == 200
        assert response.json()["configured"] is False
        assert "token" not in response.text and "api_key" not in response.text


def test_parse_request_and_actual_body_size_limits():
    with client() as c:
        headers = {"Authorization": "Bearer test-only-token"}
        result = c.post("/api/parse", headers=headers, json={"text": "午餐15元", "reference_date": "2026-10-10"})
        assert result.status_code == 200 and result.json()["drafts"][0]["amount_minor"] == 1500
        assert c.post("/api/parse", headers=headers, json={"text": " ", "reference_date": "invalid"}).status_code == 422
        assert c.post("/api/parse", headers=headers, content=b"x" * 1_400_001).status_code == 413


def test_no_key_returns_clear_error_without_mock_results():
    with TestClient(create_app(Settings(), CloudProvider(Settings()))) as c:
        result = c.post("/api/parse", json={"text": "午餐15元", "reference_date": "2026-10-10"})
        assert result.status_code == 503 and "尚未配置" in result.text


def test_lan_server_requires_connection_token():
    with pytest.raises(RuntimeError): create_app(Settings(host="0.0.0.0"))


def test_retry_recording_uses_same_pipeline():
    provider = FakeProvider()
    with client(provider) as c:
        response = c.post("/api/transcribe", headers={"Authorization": "Bearer test-only-token"}, json={
            "audio_base64": base64.b64encode(wav_bytes()).decode(), "reference_date": "2026-10-10"})
        assert response.status_code == 200 and response.json()["drafts"][0]["amount_minor"] == 1500
        assert provider.session.closed and len(provider.session.pcm) == 6400


def test_live_protocol_ready_partial_final_and_cleanup():
    provider = FakeProvider()
    with client(provider) as c:
        with c.websocket_connect("/api/voice") as socket:
            socket.send_json({"type": "start", "token": "test-only-token", "reference_date": "2026-10-10"})
            assert socket.receive_json()["type"] == "ready"
            socket.send_bytes(b"\x00\x00" * 3200)
            socket.send_json({"type": "finish"})
            messages = []
            while not messages or messages[-1]["type"] != "result": messages.append(socket.receive_json())
            assert messages[-1]["result"]["drafts"][0]["amount_minor"] == 1500
            assert any(m["type"] == "partial" for m in messages)
    assert provider.session.closed


def test_live_auth_cancel_and_short_recording():
    from starlette.websockets import WebSocketDisconnect
    with client() as c:
        with c.websocket_connect("/api/voice") as socket:
            socket.send_json({"type": "start", "token": "wrong", "reference_date": "2026-10-10"})
            with pytest.raises(WebSocketDisconnect): socket.receive_json()
        with c.websocket_connect("/api/voice") as socket:
            socket.send_json({"type": "start", "token": "test-only-token", "reference_date": "2026-10-10"})
            assert socket.receive_json()["type"] == "ready"
            socket.send_json({"type": "finish"})
            while True:
                message = socket.receive_json()
                if message["type"] == "error": break
            assert "中断" in message["message"]


def test_asr_sentence_revisions_do_not_duplicate_final_sentences():
    session = SpeechSession(Settings())
    def event(name, sentence=None):
        return json.dumps({"header": {"task_id": session.task_id, "event": name}, "payload": {"output": {"sentence": sentence or {}}}})
    values = [event("result-generated", {"begin_time": 0, "text": "午餐1", "sentence_end": False}),
              event("result-generated", {"begin_time": 0, "text": "午餐15元。", "sentence_end": True}),
              event("result-generated", {"begin_time": 0, "text": "午餐15元。", "sentence_end": True}),
              event("result-generated", {"begin_time": 2000, "text": "地铁8元。", "sentence_end": True}), event("task-finished")]
    class Wire:
        async def recv(self): return values.pop(0)
    session.ws = Wire()
    async def run():
        return [value async for value in session.events()]
    asyncio.run(run())
    assert session.finished and session.preview == "午餐15元。地铁8元。"
