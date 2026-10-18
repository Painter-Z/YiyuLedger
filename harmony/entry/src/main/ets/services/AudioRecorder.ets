import { audio } from '@kit.AudioKit';
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { AudioDraft, ledgerStore } from './LedgerStore';

export class AudioRecorder {
  private capturer: audio.AudioCapturer | undefined = undefined;
  private file: fileIo.File | undefined = undefined;
  private draft: AudioDraft | undefined = undefined;
  private bytes: number = 0;
  private onFrame: (frame: ArrayBuffer) => void = () => {};
  private onLevel: (level: number) => void = () => {};
  private onLimit: () => void = () => {};
  private stopped: boolean = true;
  private pendingWrite: Promise<void> = Promise.resolve();
  private writeError: boolean = false;

  async start(context: common.UIAbilityContext, referenceDate: string,
    onFrame: (frame: ArrayBuffer) => void, onLevel: (level: number) => void,
    onLimit: () => void): Promise<void> {
    this.stopped = false;
    this.bytes = 0;
    this.pendingWrite = Promise.resolve(); this.writeError = false;
    this.onFrame = onFrame;
    this.onLevel = onLevel;
    this.onLimit = onLimit;
    const id: string = 'audio_' + Date.now() + '_' + Math.floor(Math.random() * 1000000);
    this.draft = { id: id, path: context.filesDir + '/' + id + '.wav', referenceDate: referenceDate, createdAt: Date.now(), resultJson: '', savedCount: 0 };
    this.file = fileIo.openSync(this.draft.path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(this.file.fd, this.header(0));
    await ledgerStore.putDraft(this.draft);
    const options: audio.AudioCapturerOptions = {
      streamInfo: {
        samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
        channels: audio.AudioChannel.CHANNEL_1,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
        encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
      },
      capturerInfo: { source: audio.SourceType.SOURCE_TYPE_MIC, capturerFlags: 0 }
    };
    try {
      this.capturer = await audio.createAudioCapturer(options);
      this.capturer.on('readData', (buffer: ArrayBuffer) => {
        if (this.stopped || !this.file) return;
        const remaining: number = 960000 - this.bytes;
        const frame: ArrayBuffer = buffer.slice(0, Math.max(0, Math.min(buffer.byteLength, remaining)));
        if (frame.byteLength > 0) {
          const fd: number = this.file.fd;
          const position: number = 44 + this.bytes;
          this.bytes += frame.byteLength;
          const header: ArrayBuffer = this.header(this.bytes);
          // Copy before returning from readData; serialize async writes without blocking capture.
          this.pendingWrite = this.pendingWrite.then(async () => {
            const length: number = await fileIo.write(fd, frame, { offset: position, length: frame.byteLength });
            if (length !== frame.byteLength) throw new Error('录音写入未完成');
            await fileIo.write(fd, header, { offset: 0, length: 44 });
          }).catch(() => { this.writeError = true; this.onLimit(); });
          this.onFrame(frame);
          const values: Int16Array = new Int16Array(frame);
          let energy: number = 0;
          for (let i: number = 0; i < values.length; i++) energy += values[i] * values[i];
          this.onLevel(Math.min(1, Math.sqrt(energy / Math.max(1, values.length)) / 8000));
        }
        if (this.bytes >= 960000) this.onLimit();
      });
      await this.capturer.start();
    } catch (error) {
      await this.cancel();
      throw new Error('录音启动失败，请检查麦克风权限');
    }
  }

  async stop(): Promise<AudioDraft | undefined> {
    if (this.stopped) return this.draft;
    this.stopped = true;
    try {
      if (this.capturer) {
        this.capturer.off('readData');
        await this.capturer.stop();
      }
    } finally {
      try {
        if (this.capturer) await this.capturer.release();
      } finally {
        this.capturer = undefined;
        if (this.file) {
          try {
            await this.pendingWrite;
            fileIo.writeSync(this.file.fd, this.header(this.bytes), { offset: 0 });
          } finally {
            fileIo.closeSync(this.file);
            this.file = undefined;
          }
        }
      }
    }
    if (this.writeError) throw new Error('录音保存失败，请检查手机存储空间');
    if (this.bytes < 6400) {
      await this.discard();
      return undefined;
    }
    return this.draft;
  }

  async cancel(): Promise<void> {
    try { await this.stop(); } finally { await this.discard(); }
  }

  private async discard(): Promise<void> {
    if (this.draft) {
      try { fileIo.unlinkSync(this.draft.path); } catch (error) {}
      await ledgerStore.removeDraft(this.draft.id);
      this.draft = undefined;
    }
  }

  private header(size: number): ArrayBuffer {
    const buffer: ArrayBuffer = new ArrayBuffer(44);
    const view: DataView = new DataView(buffer);
    const riff: number[] = [82, 73, 70, 70, 0, 0, 0, 0, 87, 65, 86, 69, 102, 109, 116, 32];
    riff.forEach((value: number, index: number) => view.setUint8(index, value));
    view.setUint32(4, 36 + size, true);
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, 16000, true);
    view.setUint32(28, 32000, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    view.setUint32(36, 0x61746164, true);
    view.setUint32(40, size, true);
    return buffer;
  }
}
