import { http, webSocket } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { fileIo } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { CustomCategory, dateKey, isDateKey } from '../model/Ledger';
import { CloudProposalResult, RecognitionResult, RecognitionSession, SpeechAccumulator } from '../model/CloudParsing';
import { RecognitionReview, recognizeGrounded } from '../model/CloudRecognition';
import { SYSTEM_PROMPT } from '../model/CloudPrompt';
import { AudioDraft } from './LedgerStore';

interface ChatMessage { role: string; content: string; }
interface ResponseFormat { type: string; }
interface ChatRequest { model: string; temperature: number; response_format: ResponseFormat; messages: ChatMessage[]; }
interface ParseContent { reference_date: string; text: string; custom_categories: CustomCategory[]; review?: RecognitionReview; }
interface ChatChoice { finish_reason: string; message: ChatMessage; }
interface ChatResponse { choices: ChatChoice[]; }
interface SpeechHeader { event: string; task_id: string; }
interface SpeechSentence { text?: string; begin_time?: number; sentence_end?: boolean; }
interface SpeechOutput { sentence?: SpeechSentence; }
interface SpeechPayload { output?: SpeechOutput; }
interface SpeechEvent { header?: SpeechHeader; payload?: SpeechPayload; }

export class DirectCloudClient {
  private apiKey: string;
  private custom: CustomCategory[];
  constructor(apiKey: string, custom: CustomCategory[] = []) { this.apiKey = apiKey; this.custom = custom.slice(); }

  live(reference: string, partial: (text: string) => void): RecognitionSession {
    return new DirectCloudVoice(this.apiKey, reference, partial, this.custom);
  }

  async parse(text: string, reference: string): Promise<RecognitionResult> {
    if (!this.apiKey) throw new Error('请在设置中配置自己的百炼 API Key');
    if (!text.trim() || text.length > 2000 || !isDateKey(reference)) throw new Error('记账描述或日期不正确');
    return await recognizeGrounded(text.trim(), reference, this.custom,
      (review?: RecognitionReview) => this.requestProposals(text.trim(), reference, review));
  }

  private async requestProposals(text: string, reference: string, review?: RecognitionReview): Promise<CloudProposalResult> {
    const content: ParseContent = { reference_date: reference, text: text, custom_categories: this.custom, review: review };
    const body: ChatRequest = {
      model: 'qwen-plus', temperature: 0, response_format: { type: 'json_object' },
      messages: [{ role: 'system', content: SYSTEM_PROMPT }, { role: 'user', content: JSON.stringify(content) }]
    };
    const request: http.HttpRequest = http.createHttp();
    let response: http.HttpResponse;
    try {
      response = await request.request('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + this.apiKey },
        extraData: JSON.stringify(body), connectTimeout: 15000, readTimeout: review ? 15000 : 45000,
        expectDataType: http.HttpDataType.STRING
      });
    } catch (error) { throw new Error('未能连接百炼，请检查手机网络，稍后可重试'); }
    finally { request.destroy(); }
    if (response.responseCode === 401 || response.responseCode === 403) throw new Error('百炼密钥或权限不可用，请在设置中检查');
    if (response.responseCode === 429) throw new Error('百炼调用暂受限制，请检查账户额度或稍后重试');
    if (response.responseCode !== 200) throw new Error('百炼解析暂不可用，请稍后重试或手动记账');
    try {
      const chat: ChatResponse = JSON.parse(response.result as string) as ChatResponse;
      if (!Array.isArray(chat.choices) || !chat.choices.length || chat.choices[0].finish_reason === 'length' ||
        typeof chat.choices[0].message.content !== 'string') throw new Error('结果不完整');
      return JSON.parse(chat.choices[0].message.content) as CloudProposalResult;
    } catch (error) { throw new Error('识别结果未通过核对，请重试或手动记账'); }
  }

  async checkConnection(): Promise<void> { await this.parse('连接测试，没有需要记录的账目', dateKey()); }

  async retry(draft: AudioDraft): Promise<RecognitionResult> {
    const stat: fileIo.Stat = fileIo.statSync(draft.path);
    if (stat.size < 6444 || stat.size > 960044) throw new Error('录音草稿格式或时长不正确');
    const file: fileIo.File = fileIo.openSync(draft.path, fileIo.OpenMode.READ_ONLY);
    const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
    try { fileIo.readSync(file.fd, buffer); } finally { fileIo.closeSync(file); }
    const header: DataView = new DataView(buffer);
    if (header.getUint32(0, false) !== 0x52494646 || header.getUint32(8, false) !== 0x57415645 ||
      header.getUint16(20, true) !== 1 || header.getUint16(22, true) !== 1 || header.getUint32(24, true) !== 16000 ||
      header.getUint16(34, true) !== 16 || header.getUint32(36, false) !== 0x64617461 ||
      header.getUint32(40, true) !== stat.size - 44) throw new Error('录音草稿格式不正确');
    const live: DirectCloudVoice = new DirectCloudVoice(this.apiKey, draft.referenceDate, () => {}, this.custom);
    for (let start: number = 44; start < buffer.byteLength; start += 3200) live.push(buffer.slice(start, Math.min(start + 3200, buffer.byteLength)));
    return await live.finish();
  }
}

export class DirectCloudVoice implements RecognitionSession {
  private ws: webSocket.WebSocket = webSocket.createWebSocket();
  private client: DirectCloudClient;
  private reference: string;
  private taskId: string = util.generateRandomUUID(false).replace(/-/g, '');
  private accumulator: SpeechAccumulator = new SpeechAccumulator();
  private queue: ArrayBuffer[] = [];
  private total: number = 0;
  private ready: boolean = false;
  private ending: boolean = false;
  private finishSent: boolean = false;
  private pumping: boolean = false;
  private speechComplete: boolean = false;
  private closed: boolean = false;
  private timer: number = -1;
  private resolve: (result: RecognitionResult) => void = () => {};
  private reject: (error: Error) => void = () => {};
  readonly result: Promise<RecognitionResult>;

  constructor(apiKey: string, reference: string, partial: (text: string) => void, custom: CustomCategory[] = []) {
    this.client = new DirectCloudClient(apiKey, custom); this.reference = reference;
    this.result = new Promise<RecognitionResult>((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
    this.result.catch(() => {});
    this.ws.on('open', () => {
      const command: string = '{"header":{"action":"run-task","task_id":"' + this.taskId +
        '","streaming":"duplex"},"payload":{"task_group":"audio","task":"asr","function":"recognition",' +
        '"model":"fun-asr-realtime","parameters":{"format":"pcm","sample_rate":16000},"input":{}}}';
      this.ws.send(command).catch(() => this.fail('语音任务启动失败，录音可稍后重试'));
    });
    this.ws.on('message', (error: BusinessError, value: string | ArrayBuffer) => {
      if (this.closed || this.speechComplete || typeof value !== 'string') return;
      try {
        const event: SpeechEvent = JSON.parse(value) as SpeechEvent;
        if (!event.header || event.header.task_id !== this.taskId) return;
        if (event.header.event === 'task-started') { this.ready = true; this.pump(); }
        if (event.header.event === 'task-failed') { this.fail('百炼语音识别失败，请检查密钥权限、额度或网络'); return; }
        if (event.header.event === 'result-generated' && event.payload && event.payload.output && event.payload.output.sentence) {
          const sentence: SpeechSentence = event.payload.output.sentence;
          if (typeof sentence.text === 'string') partial(this.accumulator.accept(sentence.begin_time || 0, sentence.text, sentence.sentence_end === true));
        }
        if (event.header.event === 'task-finished') this.complete();
      } catch (error) { this.fail('语音结果格式不正确，录音可稍后重试'); }
    });
    this.ws.on('error', () => { if (!this.speechComplete) this.fail('无法连接百炼语音服务，请检查手机网络或密钥'); });
    this.ws.on('close', () => { if (!this.closed && !this.speechComplete) this.fail('语音连接中断，录音可稍后重试'); });
    if (!apiKey) { this.fail('请在设置中配置自己的百炼 API Key'); return; }
    this.timer = setTimeout(() => this.fail('识别等待超时，录音已保留，可稍后重试'), 90000);
    this.ws.connect('wss://dashscope.aliyuncs.com/api-ws/v1/inference', { header: { 'Authorization': 'Bearer ' + apiKey } })
      .catch(() => this.fail('无法连接百炼语音服务，请检查手机网络或密钥'));
  }

  push(frame: ArrayBuffer): void {
    if (this.closed || this.finishSent) return;
    this.total += frame.byteLength;
    if (frame.byteLength % 2 || this.total > 960000) { this.fail('录音超过30秒或格式不正确'); return; }
    this.queue.push(frame); this.pump();
  }
  finish(): Promise<RecognitionResult> { this.ending = true; this.pump(); return this.result; }
  private async pump(): Promise<void> {
    if (!this.ready || this.pumping || this.closed || this.finishSent) return;
    this.pumping = true;
    try {
      while (this.queue.length && !this.closed) {
        const frame: ArrayBuffer | undefined = this.queue.shift();
        if (frame) await this.ws.send(frame);
      }
      if (this.ending && !this.closed) {
        if (this.total < 6400) { this.fail('录音太短，请按住后再说'); return; }
        this.finishSent = true;
        await this.ws.send('{"header":{"action":"finish-task","task_id":"' + this.taskId + '","streaming":"duplex"},"payload":{"input":{}}}');
      }
    } catch (error) { this.fail('语音发送失败，录音可稍后重试'); }
    finally { this.pumping = false; }
  }
  private async complete(): Promise<void> {
    if (this.speechComplete || this.closed) return;
    this.speechComplete = true;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.fail('账单提取等待超时，录音已保留，可稍后重试'), 75000);
    this.ws.close().catch(() => {});
    const transcript: string = this.accumulator.finish().trim();
    if (!transcript) {
      this.resolve({ transcript: '', drafts: [], issues: ['没有听清楚，请重新说一遍'] }); this.close(); return;
    }
    try {
      const result: RecognitionResult = await this.client.parse(transcript, this.reference);
      if (!this.closed) { this.resolve(result); this.close(); }
    } catch (error) { this.fail(error instanceof Error ? error.message : '解析失败，录音可稍后重试'); }
  }
  cancel(): void { if (!this.closed) { this.reject(new Error('已取消录音')); this.close(); } }
  private fail(message: string): void { if (!this.closed) { this.reject(new Error(message)); this.close(); } }
  private close(): void { this.closed = true; clearTimeout(this.timer); this.queue = []; this.ws.close().catch(() => {}); }
}
