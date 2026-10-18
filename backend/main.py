import asyncio
import base64
from contextlib import asynccontextmanager
from datetime import date
import hmac
import io
import json
import wave
from fastapi import FastAPI, Depends, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from .config import Settings, get_settings
from .domain import MAX_AUDIO_BYTES, ParseRequest, TranscribeRequest
from .providers import CloudProvider, ProviderError, transcribe_pcm


class BodyLimitMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope['method'] != 'POST':
            return await self.app(scope, receive, send)
        total = 0
        parts = []
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            chunk = message.get('body', b'')
            total += len(chunk)
            if total > 1_400_000:
                response = JSONResponse({'detail': '请求过大'}, status_code=413)
                await response(scope, receive, send)
                return
            parts.append(chunk)
            if not message.get('more_body', False):
                break
        body = b''.join(parts)
        delivered = False
        async def replay():
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {'type': 'http.request', 'body': body, 'more_body': False}
        await self.app(scope, replay, send)


def decode_wav(value: str) -> bytes:
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) > MAX_AUDIO_BYTES + 4096:
            raise ValueError()
        with wave.open(io.BytesIO(raw), "rb") as audio:
            if audio.getnchannels() != 1 or audio.getsampwidth() != 2 or audio.getframerate() != 16000 or audio.getcomptype() != "NONE":
                raise ValueError()
            pcm = audio.readframes(audio.getnframes())
            if len(pcm) != audio.getnframes() * 2 or not 6400 <= len(pcm) <= MAX_AUDIO_BYTES:
                raise ValueError()
            return pcm
    except Exception:
        raise HTTPException(422, "录音需为0.2–30秒、16kHz、单声道、16位PCM WAV") from None


def create_app(settings: Settings | None = None, provider=None) -> FastAPI:
    settings = settings or get_settings()
    provider = provider or CloudProvider(settings)
    # Avoid accidentally exposing a cost-bearing proxy without authentication.
    if settings.host not in {"127.0.0.1", "localhost", "::1"} and not settings.api_token:
        raise RuntimeError("局域网监听前请设置 LEDGER_API_TOKEN，推荐使用 tools/start-backend.ps1")
    gate = asyncio.Semaphore(3)

    @asynccontextmanager
    async def lifespan(app):
        yield

    app = FastAPI(title="一语账 · 识别服务", version="0.1.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    def authorized(token: str) -> bool:
        return bool(settings.api_token) and hmac.compare_digest(token.encode('utf-8'), settings.api_token.encode('utf-8'))

    async def auth(authorization: str = Header(default="")):
        if settings.api_token:
            if not authorized(authorization.removeprefix("Bearer ")):
                raise HTTPException(401, "连接口令不正确")
        elif settings.host not in {"127.0.0.1", "localhost", "::1"}:
            raise HTTPException(503, "服务未配置连接口令")

    app.add_middleware(BodyLimitMiddleware)

    @app.exception_handler(ProviderError)
    async def provider_error(request, error):
        return JSONResponse({"detail": str(error)}, status_code=503)

    @app.get("/health", dependencies=[Depends(auth)])
    async def health():
        return {"status": "ok", "configured": bool(settings.api_key), "protocol_version": 1,
                "storage": "none", "max_recording_seconds": 30}

    @app.post("/api/parse", dependencies=[Depends(auth)])
    async def parse(request: ParseRequest):
        async with gate:
            return await provider.parse(request.text, request.reference_date)

    @app.post("/api/transcribe", dependencies=[Depends(auth)])
    async def transcribe(request: TranscribeRequest):
        pcm = decode_wav(request.audio_base64)
        async with gate:
            text = await transcribe_pcm(provider, pcm)
            if not text:
                raise HTTPException(422, "没有听清楚，请重试或手动输入")
            return await provider.parse(text, request.reference_date)

    @app.websocket("/api/voice")
    async def voice(socket: WebSocket):
        await socket.accept()
        try:
            start = await asyncio.wait_for(socket.receive_json(), 8)
            if start.get("type") != "start" or (settings.api_token and not authorized(str(start.get("token", "")))):
                await socket.close(4401, "连接口令不正确")
                return
            reference = date.fromisoformat(start["reference_date"])
            if not 1900 <= reference.year <= 2199:
                raise ValueError()
            async with gate:
                async with provider.speech() as session:
                    await socket.send_json({"type": "ready"})
                    async def receive_cloud():
                        async for text in session.events():
                            await socket.send_json({"type": "partial", "text": text})
                    cloud_task = asyncio.create_task(receive_cloud())
                    try:
                        total = 0
                        async with asyncio.timeout(50):
                            while True:
                                message = await socket.receive()
                                if message["type"] == "websocket.disconnect":
                                    return
                                pcm = message.get("bytes")
                                if pcm is not None:
                                    total += len(pcm)
                                    if total > MAX_AUDIO_BYTES or len(pcm) % 2 or len(pcm) > 65536:
                                        raise ValueError("录音超过限制")
                                    await session.send(pcm)
                                else:
                                    command = json.loads(message.get("text", "{}"))
                                    if command.get("type") == "cancel":
                                        return
                                    if command.get("type") == "finish":
                                        if total < 6400:
                                            raise ValueError("录音太短")
                                        await session.finish()
                                        break
                            await cloud_task
                        text = session.preview.strip()
                        if not text:
                            raise ProviderError("没有听清楚，请重试或手动输入")
                        result = await provider.parse(text, reference)
                        await socket.send_json({"type": "result", "result": result.model_dump()})
                    finally:
                        cloud_task.cancel()
                        await asyncio.gather(cloud_task, return_exceptions=True)
        except WebSocketDisconnect:
            pass
        except ProviderError as error:
            await safe_error(socket, str(error))
        except Exception:
            await safe_error(socket, "语音连接中断或请求格式不正确，录音可稍后重试")
        finally:
            try:
                await socket.close()
            except Exception:
                pass

    return app


async def safe_error(socket: WebSocket, message: str):
    try:
        await socket.send_json({"type": "error", "message": message})
    except Exception:
        pass


app = create_app()
