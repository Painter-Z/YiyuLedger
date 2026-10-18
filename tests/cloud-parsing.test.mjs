import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';

registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(specifier === './Ledger' && context.parentURL?.endsWith('/model/CloudParsing.ts') ? './Ledger.ts' : specifier, context);
} });
const { evidenceMinor, normalizeCloudResult, SpeechAccumulator } = await import('../harmony/entry/src/main/ets/model/CloudParsing.ts');
const proposal = (overrides = {}) => ({ kind: 'expense', title: '盖饭', currency: 'CNY', amount_text: '15元', amount_yuan: '15.00',
  category_id: 'food', occurred_on: '2026-10-10', date_text: '', time_hint: '中午', note: '', ...overrides });
const parse = (p = proposal(), text = '中午吃盖饭15元', reference = '2026-10-10') => normalizeCloudResult({ entries: [{source_text:text,title_text:p.title,...p}] }, text, reference).drafts[0];

test('personal client verifies Chinese, scaled and decimal amounts independently', () => {
  for (const [text, cents] of [['0.29元',29],['15元',1500],['十五元',1500],['15块5毛',1550],['十五元五角六分',1556],
    ['两百零八元',20800],['一千二百三十四点五六元',123456],['一万二千元',1200000],['人民币12.34元',1234],
    ['十元半',1050],['十五块五',1550],['1.2万元',1200000],['1.234万元',1234000]]) assert.equal(evidenceMinor(text), cents, text);
  for (const text of ['0元','-15元','15.555元','两千五元','一百二元','大概十五到二十元','15美元','1e4元','NaN',''])
    assert.throws(() => evidenceMinor(text), text);
});

test('model money must quote the transcript and agree with independently parsed cents', () => {
  assert.equal(parse().amount_minor,1500);
  assert.equal(parse(proposal({amount_yuan:'1500'})).amount_minor,null);
  assert.equal(parse(proposal({amount_text:'20元',amount_yuan:'20'})).amount_minor,null);
  assert.equal(parse(proposal({amount_text:null,amount_yuan:null})).amount_minor,null);
  assert.equal(parse(proposal({amount_text:'13元',amount_yuan:'13'}),'午餐15元，不对是13元').amount_minor,1300);
  assert.equal(parse(proposal(),'午餐115元').amount_minor,null);
  assert.equal(parse(proposal(),'记录调整-15元').amount_minor,null);
});

test('foreign currencies cannot be accepted as CNY even if the model omits currency', () => {
  assert.equal(parse(proposal({amount_text:'15',amount_yuan:'15'}),'午餐15美元').amount_minor,null);
  assert.equal(parse(proposal({currency:'other'})).amount_minor,null);
});

test('dates use the recorded local reference and reject invalid model dates', () => {
  assert.equal(parse(proposal({date_text:'昨天',occurred_on:'2025-01-01'}),'昨天午餐15元').occurred_on,'2026-10-09');
  assert.equal(parse(proposal({date_text:'昨天'}),'昨天午餐15元','2026-01-01').occurred_on,'2025-12-31');
  assert.equal(parse(proposal({date_text:'2月30日',occurred_on:'2026-02-30'}),'2月30日午餐15元').occurred_on,'2026-10-10');
  assert.equal(parse(proposal({occurred_on:'2025-01-01'})).occurred_on,'2026-10-10');
});

test('income, refund, transfers, debt and multiple entries retain accounting directions', () => {
  for (const [kind,category] of [['income','other'],['refund','food'],['transfer','transfer'],['debt','debt']]) {
    const draft=parse(proposal({kind})); assert.equal(draft.kind,kind); assert.equal(draft.category_id,category);
  }
  const result=normalizeCloudResult({entries:[proposal({title:'午餐',title_text:'午餐',source_text:'午餐15元'}),proposal({title:'地铁',title_text:'地铁',source_text:'地铁8元',category_id:'transport',amount_text:'8元',amount_yuan:'8'})]},'午餐15元，地铁8元','2026-10-10');
  assert.deepEqual(result.drafts.map(d=>d.amount_minor),[1500,800]);
  assert.ok(normalizeCloudResult({entries:[]},'你好','2026-10-10').issues.length);
  assert.throws(()=>normalizeCloudResult({entries:Array(21).fill(proposal())},'15元','2026-10-10'));
  assert.throws(()=>normalizeCloudResult({entries:[proposal({kind:'unknown'})]},'15元','2026-10-10'));
});

test('ASR sentence revisions replace previous text and final sentences are not duplicated', () => {
  const asr=new SpeechAccumulator();
  assert.equal(asr.accept(0,'午餐15',false),'午餐15');
  assert.equal(asr.accept(0,'午餐15元。',true),'午餐15元。');
  assert.equal(asr.accept(0,'午餐13元。',true),'午餐13元。');
  assert.equal(asr.accept(500,'地铁8元。',false),'午餐13元。地铁8元。');
  asr.accept(500,'地铁8元。',true);
  assert.equal(asr.finish(),'午餐13元。地铁8元。');
});
