import type { Category, CustomCategory } from './Ledger';
import { KINDS, categoriesForKind, isDateKey, parseMinor, shiftDays } from './Ledger';

export interface RecognizedDraft {
  kind: string;
  title: string;
  amount_minor: number | null;
  category_id: string;
  occurred_on: string;
  time_hint: string;
  note: string;
  issues: string[];
  source_text?: string;
  review_required?: boolean;
}

export interface RecognitionResult {
  transcript: string;
  drafts: RecognizedDraft[];
  issues: string[];
}

export interface RecognitionSession {
  readonly result: Promise<RecognitionResult>;
  push(frame: ArrayBuffer): void;
  finish(): Promise<RecognitionResult>;
  cancel(): void;
}

export interface CloudProposal {
  kind: string;
  currency?: string;
  title: string;
  amount_text?: string | null;
  amount_yuan?: string | null;
  category_id?: string;
  occurred_on?: string;
  date_text?: string;
  time_hint?: string;
  note?: string;
  source_text?: string;
  title_text?: string;
}

export interface CloudProposalResult { entries: CloudProposal[]; }

function digit(char: string): number {
  return [0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9]['零〇一二两三四五六七八九'.indexOf(char)];
}

function unit(char: string): number {
  return [10, 100, 1000, 10000, 100000000]['十百千万亿'.indexOf(char)] || 0;
}

// Return hundredths without binary floating point arithmetic on money.
function numberHundredths(text: string): number {
  const numeric: RegExpMatchArray | null = text.match(/^(\d+)(?:\.(\d+))?([百千万亿])?$/);
  if (numeric) {
    const scale: number = numeric[3] ? unit(numeric[3]) : 1;
    const places: number = 2 + Math.round(Math.log10(scale));
    const fraction: string = numeric[2] || '';
    if (fraction.length > places && /[1-9]/.test(fraction.slice(places))) throw new Error('金额小数位过多');
    return Number(numeric[1]) * scale * 100 + Number(fraction.slice(0, places).padEnd(places, '0'));
  }
  const parts: string[] = text.split('点');
  if (parts.length > 2 || !parts[0] || !/^[零〇一二两三四五六七八九十百千万亿]+$/.test(parts[0]) ||
    parts.length === 2 && !/^[零〇一二两三四五六七八九]{1,2}$/.test(parts[1])) throw new Error('金额表达不明确');
  if (/[百千万亿][一二两三四五六七八九]$/.test(parts[0])) throw new Error('省略位数的金额需核对');
  if (/[十百千万亿]/.test(parts[0]) && /[一二两三四五六七八九]{2}/.test(parts[0])) throw new Error('金额表达不明确');
  let total: number = 0;
  if (!/[十百千万亿]/.test(parts[0])) {
    total = Number(parts[0].split('').map((char: string) => String(digit(char))).join(''));
  } else {
    let section: number = 0;
    let value: number = 0;
    for (const char of parts[0]) {
      const factor: number = unit(char);
      if (!factor) value = digit(char);
      else {
        if (factor < 10000) section += (value || 1) * factor;
        else { total += (section + value) * factor; section = 0; }
        value = 0;
      }
    }
    total += section + value;
  }
  const fraction: string = (parts[1] || '').split('').map((char: string) => String(digit(char))).join('');
  return total * 100 + Number(fraction.padEnd(2, '0'));
}

export function evidenceMinor(text: string): number {
  if (!text || text.length > 80) throw new Error('金额表达不明确');
  let cleaned: string = text.replace(/\s|人民币|RMB|CNY|[¥￥]/gi, '');
  cleaned = cleaned.replace(/(元|块钱|块)[零〇0](?=[1-9一二两三四五六七八九]分$)/, '$1');
  // A single digit after 毛/角 is the omitted 分, e.g. 十一块八毛九 / 8毛9.
  if (/[角毛][0-9零〇一二两三四五六七八九]$/.test(cleaned)) cleaned += '分';
  const fractionOnly: RegExpMatchArray | null = cleaned.match(/^([0-9零〇一二两三四五六七八九])(?:角|毛)(?:([0-9零〇一二两三四五六七八九])分)?$/);
  const centsOnly: RegExpMatchArray | null = cleaned.match(/^([0-9零〇一二两三四五六七八九])分$/);
  if (fractionOnly || centsOnly) {
    const small: number = fractionOnly ? numberHundredths(fractionOnly[1]) / 10 +
      (fractionOnly[2] ? numberHundredths(fractionOnly[2]) / 100 : 0) : numberHundredths((centsOnly as RegExpMatchArray)[1]) / 100;
    if (small <= 0) throw new Error('金额需大于零');
    return small;
  }
  const colloquial: RegExpMatchArray | null = cleaned.match(/^(.+?)(?:块钱|块|元)([0-9零〇一二两三四五六七八九])$/);
  if (colloquial) cleaned = colloquial[1] + '块' + colloquial[2] + '毛';
  const expression: RegExp = /^([0-9零〇一二两三四五六七八九十百千万亿点.]+)(?:元|块钱|块)?(?:(半)|([0-9零〇一二两三四五六七八九])(?:角|毛))?(?:([0-9零〇一二两三四五六七八九])分)?$/;
  const match: RegExpMatchArray | null = cleaned.match(expression);
  if (!match) throw new Error('金额表达不明确');
  let minor: number = numberHundredths(match[1]);
  if (match[2]) minor += 50;
  if (match[3]) minor += numberHundredths(match[3]) / 10;
  if (match[4]) minor += numberHundredths(match[4]) / 100;
  if (!Number.isSafeInteger(minor) || minor <= 0 || minor > 999999999999) throw new Error('金额需大于零且最多两位小数');
  return minor;
}

interface SourceClaim { item: CloudProposal; source: string; start: number; end: number; valid: boolean; }

function tightenClaim(claim: SourceClaim): void {
  const name: string = typeof claim.item.title_text === 'string' ? claim.item.title_text : '';
  const money: string = typeof claim.item.amount_text === 'string' ? claim.item.amount_text : '';
  if (!name || !money) return;
  let bestStart: number = -1;
  let bestEnd: number = claim.source.length + 1;
  let nameAt: number = claim.source.indexOf(name);
  let names: number = 0;
  while (nameAt >= 0 && names++ < 64) {
    let moneyAt: number = claim.source.indexOf(money);
    let amounts: number = 0;
    while (moneyAt >= 0 && amounts++ < 64) {
      const start: number = Math.min(nameAt, moneyAt);
      const end: number = Math.max(nameAt + name.length, moneyAt + money.length);
      if (end - start < bestEnd - bestStart) { bestStart = start; bestEnd = end; }
      moneyAt = claim.source.indexOf(money, moneyAt + 1);
    }
    nameAt = claim.source.indexOf(name, nameAt + 1);
  }
  if (bestStart >= 0) {
    claim.source = claim.source.slice(bestStart, bestEnd);
    claim.start += bestStart; claim.end = claim.start + claim.source.length;
  }
}

function sourceClaims(items: CloudProposal[], transcript: string): SourceClaim[] {
  const claims: SourceClaim[] = [];
  for (const item of items) {
    if (!item || !KINDS.includes(item.kind) || typeof item.title !== 'string' || item.title.length > 80)
      throw new Error('识别结果格式不正确');
    const source: string = typeof item.source_text === 'string' ? item.source_text.trim() : '';
    let start: number = source && source.length <= 2000 ? transcript.indexOf(source) : -1;
    let available: number = start;
    while (available >= 0 && claims.some((claim: SourceClaim) => claim.start === available && claim.source === source))
      available = transcript.indexOf(source, available + 1);
    if (available >= 0) start = available;
    claims.push({ item: item, source: source, start: start, end: start + source.length, valid: start >= 0 });
  }
  // Models sometimes quote the whole paragraph for each item. Narrow literal quotes to their own anchors first.
  const overlapping: boolean[] = claims.map(() => false);
  const originalBounds: number[][] = claims.map((claim: SourceClaim) => [claim.start, claim.end]);
  for (let a: number = 0; a < claims.length; a++) {
    for (let b: number = a + 1; b < claims.length; b++) {
      if (claims[a].valid && claims[b].valid && claims[a].start < claims[b].end && claims[b].start < claims[a].end) {
        overlapping[a] = true; overlapping[b] = true;
      }
    }
  }
  claims.forEach((claim: SourceClaim, index: number) => { if (overlapping[index]) tightenClaim(claim); });
  for (let index: number = 0; index < claims.length; index++) {
    const claim: SourceClaim = claims[index];
    const previous: SourceClaim[] = claims.slice(0, index);
    if (overlapping[index] && previous.some((other: SourceClaim) => other.start === claim.start && other.source === claim.source)) {
      let next: number = transcript.indexOf(claim.source, claim.start + 1);
      while (next >= 0 && next + claim.source.length <= originalBounds[index][1]) {
        if (next >= originalBounds[index][0] && !previous.some((other: SourceClaim) => other.start < next + claim.source.length && next < other.end)) {
          claim.start = next; claim.end = next + claim.source.length; break;
        }
        next = transcript.indexOf(claim.source, next + 1);
      }
    }
  }
  // Quotes still overlapping after narrowing cannot safely establish separate transactions.
  for (let a: number = 0; a < claims.length; a++) {
    for (let b: number = a + 1; b < claims.length; b++) {
      if (claims[a].start >= 0 && claims[b].start >= 0 && claims[a].start < claims[b].end && claims[b].start < claims[a].end) {
        claims[a].valid = false; claims[b].valid = false;
      }
    }
  }
  return claims.sort((a: SourceClaim, b: SourceClaim) => (a.start < 0 ? transcript.length : a.start) - (b.start < 0 ? transcript.length : b.start));
}

function amountPosition(transcript: string, claim: SourceClaim, evidence: string): number {
  if (!evidence) return -1;
  let offset: number = claim.source.indexOf(evidence);
  while (offset >= 0) {
    const start: number = claim.start + offset;
    const before: string = transcript[start - 1] || '';
    const after: string = transcript[start + evidence.length] || '';
    if (!/[0-9零〇一二两三四五六七八九十百千万亿点.元块角毛分负負\-−]/.test(before) &&
      !/[0-9零〇一二两三四五六七八九十百千万亿点.角毛分]/.test(after)) return offset;
    offset = claim.source.indexOf(evidence, offset + 1);
  }
  return -1;
}

function hasLaterCorrection(source: string, amountAt: number, evidence: string): boolean {
  const tail: string = source.slice(amountAt + evidence.length);
  return /(?:不对|改成|改为|更正|应该是|实际(?:是|花了))[^，。；;]{0,20}[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|毛|角|分)/.test(tail);
}

function currencyContext(transcript: string, claim: SourceClaim): string {
  if (claim.start < 0) return claim.source;
  let start: number = claim.start;
  let end: number = claim.end;
  while (start > 0 && !/[，,。；;！？!?\n]/.test(transcript[start - 1])) start--;
  while (end < transcript.length && !/[，,。；;！？!?\n]/.test(transcript[end])) end++;
  return transcript.slice(start, end);
}

export function normalizeCloudResult(raw: CloudProposalResult, transcript: string, reference: string, custom: CustomCategory[] = []): RecognitionResult {
  if (!isDateKey(reference) || !raw || !Array.isArray(raw.entries) || raw.entries.length > 20) throw new Error('识别结果格式不正确');
  const claims: SourceClaim[] = sourceClaims(raw.entries, transcript);
  const drafts: RecognizedDraft[] = claims.map((claim: SourceClaim) => {
    const item: CloudProposal = claim.item;
    const issues: string[] = [];
    let review: boolean = false;
    let title: string = '';
    const titleText: string = typeof item.title_text === 'string' ? item.title_text.trim() : '';
    const summary: boolean = /^(?:总计|合计|一共|总共|共计|找零|余额|单价)/.test(claim.source);
    if (!claim.valid || summary) {
      review = true; issues.push(summary ? '这段原话可能是合计或找零，请核对是否需要记账' : '这笔账单的原话对应不明确，请核对名称和金额');
    } else if (titleText && titleText.length <= 80 && claim.source.includes(titleText) &&
      !/[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|毛|角|分)|花了|支付了/.test(titleText)) {
      if (item.title.trim() && titleText.includes(item.title.trim())) title = item.title.trim();
      else { title = titleText; issues.push('名称已按对应原话校正，请确认'); }
    } else { review = true; issues.push('名称缺少这笔原话的依据，请补充名称'); }
    let minor: number | null = null;
    const evidence: string = typeof item.amount_text === 'string' ? item.amount_text : '';
    const foreign: boolean = item.currency !== undefined && item.currency !== 'CNY' ||
      /美元|美金|港元|港币|欧元|日元|英镑|韩元|新币|澳元|外币|USD|HKD|EUR|JPY/i.test(currencyContext(transcript, claim));
    const amountAt: number = claim.valid ? amountPosition(transcript, claim, evidence) : -1;
    if (foreign) issues.push('当前仅支持人民币，外币金额请自行核对换算后填写');
    else if (claim.valid && !summary && evidence && evidence.length <= 80 && amountAt >= 0 && !hasLaterCorrection(claim.source, amountAt, evidence)) {
      try {
        const checked: number = evidenceMinor(evidence);
        if (typeof item.amount_yuan !== 'string' || parseMinor(item.amount_yuan) !== checked) throw new Error('金额不一致');
        minor = checked;
      } catch (error) { review = true; issues.push('金额待核对，请手动填写'); }
    } else {
      if (evidence) review = true;
      issues.push(evidence ? '金额与这笔原话不一致，请核对填写' : '没有明确的金额，请手动填写');
    }
    const allowed: string[] = categoriesForKind(item.kind, custom).map((category: Category) => category.id);
    const category: string = typeof item.category_id === 'string' && allowed.includes(item.category_id) ? item.category_id :
      item.kind === 'transfer' ? 'transfer' : item.kind === 'debt' ? 'debt' : 'other';
    if (category !== item.category_id) issues.push('分类已调整，请确认');
    let occurred: string = reference;
    const dateText: string = typeof item.date_text === 'string' ? item.date_text : '';
    if (dateText && transcript.includes(dateText)) {
      const relative: number = ['前天', '昨天', '今天', '明天', '后天'].indexOf(dateText);
      if (relative >= 0) occurred = shiftDays(reference, relative - 2);
      else if (typeof item.occurred_on === 'string' && isDateKey(item.occurred_on)) {
        occurred = item.occurred_on; issues.push('日期根据原文推算，请确认');
      } else issues.push('日期未能确定，暂用录入日期');
    } else if (item.occurred_on && item.occurred_on !== reference) issues.push('日期缺少原文依据，暂用录入日期');
    if (!isDateKey(occurred)) { occurred = reference; issues.push('日期超出支持范围，请确认'); }
    return { kind: item.kind, title: title, amount_minor: minor, category_id: category,
      occurred_on: occurred, time_hint: typeof item.time_hint === 'string' && transcript.includes(item.time_hint) ? item.time_hint.slice(0, 40) : '',
      note: typeof item.note === 'string' ? item.note.slice(0, 500) : '', issues: issues,
      source_text: claim.valid && !summary ? claim.source : '', review_required: review };
  });
  return { transcript: transcript, drafts: drafts, issues: drafts.length ? [] : ['没有识别到可记账内容，请修改描述或手动记账'] };
}

export class SpeechAccumulator {
  private sentences: Map<number, string> = new Map<number, string>();
  preview: string = '';
  accept(begin: number, text: string, final: boolean): string {
    if (final) this.sentences.set(begin, text);
    const ordered: string = Array.from(this.sentences.keys()).sort((a: number, b: number) => a - b)
      .map((key: number) => this.sentences.get(key) || '').join('');
    this.preview = final ? ordered : ordered + text;
    return this.preview;
  }
  finish(): string {
    return Array.from(this.sentences.keys()).sort((a: number, b: number) => a - b)
      .map((key: number) => this.sentences.get(key) || '').join('') || this.preview;
  }
}
