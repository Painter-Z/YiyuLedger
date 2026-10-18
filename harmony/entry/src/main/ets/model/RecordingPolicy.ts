import type { RecognitionResult } from './CloudParsing';

export const FAILED_RECORDING_TTL: number = 24 * 60 * 60 * 1000;

export interface RecordingState {
  createdAt: number;
  resultJson: string;
  savedCount: number;
  resultOffset?: number;
}

export function pendingRecognition(draft: RecordingState): RecognitionResult {
  const result: RecognitionResult = JSON.parse(draft.resultJson) as RecognitionResult;
  const offset: number = draft.resultOffset || 0;
  if (!result || !Array.isArray(result.drafts) || !Array.isArray(result.issues) || typeof result.transcript !== 'string' ||
    !Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(draft.savedCount) || draft.savedCount < offset ||
    draft.savedCount > offset + result.drafts.length) throw new Error('待确认记录进度异常，请保留并检查');
  return { transcript: result.transcript, drafts: result.drafts.slice(draft.savedCount - offset), issues: result.issues };
}

// Once a bill has been saved, its proposal and original recording text are no longer a second ledger copy.
export function compactRecognition(draft: RecordingState): string {
  const pending: RecognitionResult = pendingRecognition(draft);
  return JSON.stringify({ transcript: '', drafts: pending.drafts, issues: [] });
}

// Only failed recognition needs audio. Recognized proposals can resume from JSON alone.
export function recordingDisposition(draft: RecordingState, now: number): string {
  if (draft.resultJson) {
    try {
      const result: RecognitionResult = JSON.parse(draft.resultJson) as RecognitionResult;
      if (!result || !Array.isArray(result.drafts)) return 'keep';
      if (result.drafts.length === 0 || draft.savedCount >= result.drafts.length + (draft.resultOffset || 0)) return 'discard';
      return 'release-audio';
    } catch (error) {
      // Keep damaged proposal metadata for an explicit user decision; never discard unsaved bills by age.
      return 'keep';
    }
  }
  return now - draft.createdAt >= FAILED_RECORDING_TTL ? 'discard' : 'keep';
}
