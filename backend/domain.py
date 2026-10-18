"""Validate model proposals before they can become editable ledger drafts."""
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
import re
from typing import Literal
from pydantic import BaseModel, Field, field_validator

Kind = Literal["expense", "income", "refund", "transfer", "debt"]
EXPENSE_CATEGORIES = {"food", "transport", "shopping", "daily", "housing", "entertainment", "health", "education", "travel", "social", "other"}
INCOME_CATEGORIES = {"salary", "bonus", "freelance", "investment", "social", "other"}
MAX_AUDIO_BYTES = 16000 * 2 * 30


class ParseRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    reference_date: date

    @field_validator("text")
    @classmethod
    def nonempty(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("请填写记账描述")
        return value.strip()

    @field_validator("reference_date")
    @classmethod
    def supported_date(cls, value: date) -> date:
        if not 1900 <= value.year <= 2199:
            raise ValueError("日期超出支持范围")
        return value


class TranscribeRequest(BaseModel):
    audio_base64: str = Field(min_length=44, max_length=1_300_000)
    reference_date: date

    @field_validator("reference_date")
    @classmethod
    def supported_date(cls, value: date) -> date:
        return ParseRequest.supported_date(value)


class Proposal(BaseModel):
    kind: Kind
    currency: Literal["CNY", "other"] = "CNY"
    title: str = Field(min_length=1, max_length=80)
    amount_text: str | None = Field(default=None, max_length=80)
    amount_yuan: str | None = Field(default=None, max_length=30)
    category_id: str = Field(default="other", max_length=40)
    occurred_on: str | None = Field(default=None, max_length=10)
    date_text: str = Field(default="", max_length=40)
    time_hint: str = Field(default="", max_length=40)
    note: str = Field(default="", max_length=500)


class Draft(BaseModel):
    kind: Kind
    title: str
    amount_minor: int | None
    category_id: str
    occurred_on: str
    time_hint: str
    note: str
    issues: list[str]


class ParseResult(BaseModel):
    transcript: str
    drafts: list[Draft]
    issues: list[str] = Field(default_factory=list)


def chinese_number(text: str) -> Decimal:
    digits = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
    units = {"十": 10, "百": 100, "千": 1000, "万": 10000, "亿": 100000000}
    if re.fullmatch(r"\d+(?:\.\d+)?", text):
        return Decimal(text)
    scaled = re.fullmatch(r"(\d+(?:\.\d+)?)(百|千|万|亿)", text)
    if scaled:
        return Decimal(scaled[1]) * units[scaled[2]]
    whole, _, fraction = text.partition("点")
    if not whole or any(c not in digits and c not in units for c in whole) or any(c not in digits for c in fraction):
        raise ValueError("金额表达不明确")
    if not fraction and re.search(r"[百千万亿][一二两三四五六七八九]$", whole):
        raise ValueError("省略位数的口语金额需确认")
    if not any(c in units for c in whole):
        total = int("".join(str(digits[c]) for c in whole))
    else:
        total = section = number = 0
        for char in whole:
            if char in digits:
                number = digits[char]
            else:
                unit = units[char]
                if unit < 10000:
                    section += (number or 1) * unit
                else:
                    section += number
                    total += section * unit
                    section = 0
                number = 0
        total += section + number
    return Decimal(str(total) + ("." + "".join(str(digits[c]) for c in fraction) if fraction else ""))


def evidence_minor(text: str) -> int:
    """Accept only an amount expression, never compute an amount from prose."""
    cleaned = re.sub(r"\s|人民币|RMB|CNY|[¥￥]", "", text, flags=re.I)
    colloquial = re.fullmatch(r"(.+?)块([0-9一二两三四五六七八九])", cleaned)
    if colloquial:
        cleaned = colloquial[1] + "块" + colloquial[2] + "毛"
    number = r"[0-9零〇一二两三四五六七八九十百千万亿点.]+"
    match = re.fullmatch(rf"({number})(?:元|块钱|块)?(?:(半)|({number})(?:角|毛))?(?:({number})分)?", cleaned)
    if not match:
        raise ValueError("金额表达不明确")
    yuan = chinese_number(match[1])
    if match[2]:
        yuan += Decimal("0.5")
    if match[3]:
        yuan += chinese_number(match[3]) / 10
    if match[4]:
        yuan += chinese_number(match[4]) / 100
    cents = yuan * 100
    if cents != cents.to_integral_value() or cents <= 0 or cents > 999_999_999_999:
        raise ValueError("金额需大于零且最多两位小数")
    return int(cents)


def normalize_proposal(p: Proposal, transcript: str, reference: date) -> Draft:
    issues: list[str] = []
    minor: int | None = None
    # The model must quote original evidence. Its numeric answer is checked independently.
    foreign = p.currency != "CNY"
    if p.amount_text:
        for match in re.finditer(re.escape(p.amount_text), transcript):
            context = transcript[max(0, match.start() - 8):match.end() + 8]
            foreign = foreign or bool(re.search(r"美元|美金|港元|港币|欧元|日元|英镑|韩元|新币|澳元|外币|USD|HKD|EUR|JPY", context, re.I))
    if foreign:
        issues.append("当前仅支持人民币，外币金额请自行核对换算后填写")
    elif p.amount_text and p.amount_text in transcript:
        try:
            evidence = evidence_minor(p.amount_text)
            model_value = Decimal(p.amount_yuan or "NaN") * 100
            if not model_value.is_finite() or model_value != evidence:
                raise ValueError("模型金额和原文不一致")
            minor = evidence
        except (ValueError, InvalidOperation):
            issues.append("金额待核对，请手动填写")
    else:
        issues.append("没有明确的金额，请手动填写")
    allowed = INCOME_CATEGORIES if p.kind == "income" else EXPENSE_CATEGORIES
    if p.kind == "transfer":
        allowed = {"transfer"}
    if p.kind == "debt":
        allowed = {"debt"}
    category = p.category_id if p.category_id in allowed else next(iter(allowed)) if len(allowed) == 1 else "other"
    if category != p.category_id:
        issues.append("分类已调整，请确认")
    occurred = reference
    if p.date_text and p.date_text in transcript:
        # Resolve common relative words independently, and verify explicit dates below.
        relative = {"今天": 0, "昨天": -1, "前天": -2, "明天": 1, "后天": 2}
        if p.date_text in relative:
            occurred = reference + timedelta(days=relative[p.date_text])
        else:
            try:
                proposed = date.fromisoformat(p.occurred_on or "")
                if not 1900 <= proposed.year <= 2199:
                    raise ValueError()
                occurred = proposed
                issues.append("日期根据原文推算，请确认")
            except ValueError:
                issues.append("日期未能确定，暂用录入日期")
    elif p.occurred_on and p.occurred_on != reference.isoformat():
        issues.append("日期缺少原文依据，暂用录入日期")
    time_hint = p.time_hint if p.time_hint in transcript else ""
    return Draft(kind=p.kind, title=p.title.strip(), amount_minor=minor, category_id=category,
                 occurred_on=occurred.isoformat(), time_hint=time_hint, note=p.note, issues=issues)


def normalize_result(raw: dict, transcript: str, reference: date) -> ParseResult:
    items = raw.get("entries")
    if not isinstance(items, list) or len(items) > 20:
        raise ValueError("识别结果格式不正确")
    drafts = [normalize_proposal(Proposal.model_validate(item), transcript, reference) for item in items]
    return ParseResult(transcript=transcript, drafts=drafts,
                       issues=[] if drafts else ["没有识别到可记账内容，请修改描述或手动记账"])
