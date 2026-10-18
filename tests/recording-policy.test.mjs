import test from 'node:test';
import assert from 'node:assert/strict';
import { compactRecognition, pendingRecognition, recordingDisposition, FAILED_RECORDING_TTL } from '../harmony/entry/src/main/ets/model/RecordingPolicy.ts';

const now = Date.parse('2026-10-11T10:00:00+08:00');
const draft = (overrides = {}) => ({ createdAt: now, resultJson: '', savedCount: 0, ...overrides });
const cache = count => JSON.stringify({ transcript: '记账描述', drafts: Array.from({ length: count }, () => ({ title: '盖饭' })), issues: [] });

test('no bookkeeping content and fully confirmed recordings are removed immediately', () => {
  assert.equal(recordingDisposition(draft({ resultJson: cache(0) }), now), 'discard');
  assert.equal(recordingDisposition(draft({ resultJson: cache(2), savedCount: 2 }), now), 'discard');
  assert.equal(recordingDisposition(draft({ resultJson: cache(2), savedCount: 3 }), now), 'discard');
});

test('unsaved and partially confirmed bills keep proposal metadata without retaining audio', () => {
  for (const savedCount of [0, 1]) {
    assert.equal(recordingDisposition(draft({ resultJson: cache(2), savedCount, createdAt: now - 30 * FAILED_RECORDING_TTL }), now), 'release-audio');
  }
});

test('failed recordings expire at 24 hours while a backwards clock does not erase new input', () => {
  assert.equal(recordingDisposition(draft({ createdAt: now - FAILED_RECORDING_TTL + 1 }), now), 'keep');
  assert.equal(recordingDisposition(draft({ createdAt: now - FAILED_RECORDING_TTL }), now), 'discard');
  assert.equal(recordingDisposition(draft({ createdAt: now + 3600000 }), now), 'keep');
});

test('damaged proposal metadata is never silently discarded as an expired failure', () => {
  for (const resultJson of ['{broken', 'null', '{}']) {
    assert.equal(recordingDisposition(draft({ createdAt: now - 30 * FAILED_RECORDING_TTL, resultJson }), now), 'keep');
  }
});

test('saving compacts confirmed proposal copies while preserving other pending bills and stable progress', () => {
  const legacy = draft({savedCount:1, resultJson:JSON.stringify({transcript:'米线9元，猫粮15元', drafts:[{title:'米线',amount_minor:900},{title:'猫粮',amount_minor:1500}],issues:[]})});
  const compacted = {...legacy,resultJson:compactRecognition(legacy),resultOffset:1};
  assert.equal(compacted.resultJson.includes('米线'),false);
  assert.equal(compacted.resultJson.includes('900'),false);
  assert.equal(pendingRecognition(compacted).drafts.length,1);
  assert.equal(pendingRecognition(compacted).drafts[0].title,'猫粮');
  assert.equal(recordingDisposition(compacted,now),'release-audio');
  const savedAgain = {...compacted,savedCount:2};
  const completed = {...savedAgain,resultJson:compactRecognition(savedAgain),resultOffset:2};
  assert.equal(pendingRecognition(completed).drafts.length,0);
  assert.equal(recordingDisposition(completed,now),'discard');
});

test('invalid cache progress is rejected instead of silently skipping a pending bill', () => {
  assert.throws(()=>pendingRecognition(draft({savedCount:1,resultOffset:2,resultJson:cache(1)})));
  assert.throws(()=>compactRecognition(draft({savedCount:3,resultJson:cache(2)})));
});

test('saving removes the confirmed source quote while retaining only the next unsaved transaction quote', () => {
  const first={title:'保障餐',amount_minor:800,source_text:'今天吃了保障餐花了八块',review_required:false};
  const second={title:'粥',amount_minor:250,source_text:'又喝了一碗粥花了两块五',review_required:false};
  const state=draft({savedCount:1,resultJson:JSON.stringify({transcript:first.source_text+'。'+second.source_text,drafts:[first,second],issues:[]})});
  const saved=compactRecognition(state);
  assert.equal(saved.includes(first.source_text),false);
  assert.equal(saved.includes('保障餐'),false);
  const next=pendingRecognition({...state,resultJson:saved,resultOffset:1});
  assert.equal(next.transcript,'');
  assert.equal(next.drafts[0].source_text,second.source_text);
});
