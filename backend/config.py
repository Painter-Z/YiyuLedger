"""Local configuration. Never log configuration values or provider exceptions."""
import os
from dataclasses import dataclass
from pathlib import Path


def load_env(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        key, sep, value = line.strip().partition("=")
        if sep and key and not key.startswith("#"):
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


@dataclass(frozen=True)
class Settings:
    api_key: str = ""
    workspace_id: str = ""
    api_token: str = ""
    asr_model: str = "fun-asr-realtime"
    text_model: str = "qwen-plus"
    host: str = "127.0.0.1"
    port: int = 8787

    @property
    def asr_url(self) -> str:
        if self.workspace_id:
            # Workspace IDs are identifiers, never arbitrary hosts/URLs.
            import re
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", self.workspace_id):
                raise ValueError("DASHSCOPE_WORKSPACE_ID 格式不正确")
            return f"wss://{self.workspace_id}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
        return "wss://dashscope.aliyuncs.com/api-ws/v1/inference"


def get_settings() -> Settings:
    load_env(Path(__file__).with_name(".env"))
    return Settings(
        api_key=os.getenv("DASHSCOPE_API_KEY", ""),
        workspace_id=os.getenv("DASHSCOPE_WORKSPACE_ID", ""),
        api_token=os.getenv("LEDGER_API_TOKEN", ""),
        asr_model=os.getenv("DASHSCOPE_ASR_MODEL", "fun-asr-realtime"),
        text_model=os.getenv("DASHSCOPE_TEXT_MODEL", "qwen-plus"),
        host=os.getenv("HOST", "127.0.0.1"),
        port=int(os.getenv("PORT", "8787")),
    )
