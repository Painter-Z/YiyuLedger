import test from 'node:test';
import assert from 'node:assert/strict';
import {registerHooks} from 'node:module';
registerHooks({resolve(specifier,context,next) {
  return next(context.parentURL?.includes('/model/') && /^\.\/\w+$/.test(specifier) ? specifier+'.ts' : specifier,context);
}});
const {evidenceMinor,normalizeCloudResult}=await import('../harmony/entry/src/main/ets/model/CloudParsing.ts');
const {recognizeGrounded}=await import('../harmony/entry/src/main/ets/model/CloudRecognition.ts');
const reference='2026-10-18';
const original='今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五';
const first={kind:'expense',currency:'CNY',title:'保障餐',title_text:'保障餐',source_text:'今天我吃了保障餐，花了八块',amount_text:'八块',amount_yuan:'8.00',category_id:'food'};
const second={...first,title:'粥',title_text:'粥',source_text:'又喝了一碗粥，花了两块五',amount_text:'两块五',amount_yuan:'2.50'};
const normalize=(entries,text=original)=>normalizeCloudResult({entries},text,reference);

test('spoken amounts include omitted cents, fractional-only money and mixed Chinese/Arabic forms',()=>{
  for(const [text,cents] of [['十一块八毛九',1189],['11块8毛9',1189],['十一元八角九',1189],['11块八毛9',1189],
    ['两块五',250],['十一块零毛九分',1109],['十一块零毛九',1109],['八毛九',89],['8角9分',89],['五毛',50],['九分',9],['零元八角九分',89],['十一元零九分',1109],['0元05分',5]])
    assert.equal(evidenceMinor(text),cents,text);
  for(const text of ['十一二块','十一块八九','十一块八毛九厘','十一块八毛九分一厘','零毛零','一百二元','两千五元','1元10角','十一块八毛九分五'])
    assert.throws(()=>evidenceMinor(text),text);
});

test('the reported meal and porridge remain two independently grounded transactions',()=>{
  const drafts=normalize([first,second]).drafts;
  assert.deepEqual(drafts.map(d=>[d.title,d.amount_minor,d.review_required]),[['保障餐',800,false],['粥',250,false]]);
  assert.equal(drafts[1].source_text,second.source_text);
});

test('a copied title is corrected from its own literal name evidence',()=>{
  const result=normalize([first,{...second,title:'保障餐'}]);
  assert.equal(result.drafts[1].title,'粥');
  assert.equal(result.drafts[1].review_required,false);
  assert.ok(result.drafts[1].issues.some(i=>i.includes('名称已按对应原话校正')));
});

test('copying the previous name and its evidence is blocked without inventing a replacement',()=>{
  const result=normalize([first,{...second,title:'保障餐',title_text:'保障餐'}]);
  assert.equal(result.drafts[1].title,'');
  assert.equal(result.drafts[1].amount_minor,250);
  assert.equal(result.drafts[1].review_required,true);
});

test('amount evidence cannot be borrowed from another transaction',()=>{
  const result=normalize([{...first,amount_text:'两块五',amount_yuan:'2.50'},{...second,amount_text:'八块',amount_yuan:'8.00'}]);
  assert.deepEqual(result.drafts.map(d=>d.amount_minor),[null,null]);
  assert.ok(result.drafts.every(d=>d.review_required));
});

test('genuine same-name and identical repeated purchases are retained',()=>{
  const source='保障餐八块';
  const item={...first,source_text:source};
  for(const text of ['保障餐八块。保障餐八块','早上保障餐八块，晚上保障餐八块']) {
    const result=normalize([item,item],text);
    assert.deepEqual(result.drafts.map(d=>[d.title,d.amount_minor,d.review_required]),[['保障餐',800,false],['保障餐',800,false]]);
  }
});

test('overlapping or reused source fragments are not accepted twice',()=>{
  for(const entries of [[first,{...second,source_text:first.source_text}],[first,{...second,title:'保障餐',title_text:'保障餐',source_text:original}]]) {
    const result=normalize(entries);
    assert.ok(result.drafts.every(d=>d.review_required&&d.amount_minor===null));
  }
});

test('overwide literal quotes are narrowed to disjoint name and amount anchors',()=>{
  const result=normalize([{...first,source_text:original},{...second,source_text:original}]);
  assert.deepEqual(result.drafts.map(d=>[d.title,d.amount_minor,d.review_required]),[['保障餐',800,false],['粥',250,false]]);
  assert.deepEqual(result.drafts.map(d=>d.source_text),['保障餐，花了八块','粥，花了两块五']);
});

test('broad repeated quotes can still locate two genuine identical purchases',()=>{
  const text='早上保障餐八块，晚上保障餐八块';
  const item={...first,source_text:text};
  const result=normalize([item,item],text);
  assert.deepEqual(result.drafts.map(d=>[d.title,d.amount_minor,d.review_required]),[['保障餐',800,false],['保障餐',800,false]]);
});

test('narrowing broad quotes must not hide a currency prefix or suffix',()=>{
  for(const text of ['用美元买书十五元，喝粥两块五','买书十五美元，喝粥两块五']) {
    const result=normalize([{...first,title:'书',title_text:'书',source_text:text,amount_text:'十五',amount_yuan:'15'},
      {...second,source_text:text}],text);
    assert.equal(result.drafts[0].amount_minor,null);
    assert.equal(result.drafts[1].amount_minor,250);
  }
});

test('transaction order follows source text instead of an incorrect model order',()=>{
  assert.deepEqual(normalize([second,first]).drafts.map(d=>d.title),['保障餐','粥']);
});

test('Chinese evidence cannot truncate a larger amount or omit spoken fractional digits',()=>{
  for(const [text,evidence,yuan] of [['保障餐十一块','一块','1'],['保障餐十一块八毛九','十一块','11'],['保障餐十一块八毛九','八毛九','0.89'],['保障餐负十一块','十一块','11']]) {
    const result=normalize([{...first,source_text:text,amount_text:evidence,amount_yuan:yuan}],text);
    if(text.includes('负')) assert.equal(result.drafts[0].amount_minor,null,'spoken negative cannot become positive');
    else assert.equal(result.drafts[0].amount_minor,null,text);
  }
});

test('final oral corrections win and the superseded amount requires review',()=>{
  const text='保障餐八块，不对应该是七块五';
  const bad=normalize([{...first,source_text:text}],text).drafts[0];
  const good=normalize([{...first,source_text:text,amount_text:'七块五',amount_yuan:'7.50'}],text).drafts[0];
  assert.equal(bad.amount_minor,null);
  assert.equal(good.amount_minor,750);
});

test('foreign currency in another transaction does not contaminate the CNY transaction',()=>{
  const text='粥两块五，书十五美元';
  const result=normalize([{...second,source_text:'粥两块五'},{...first,title:'书',title_text:'书',source_text:'书十五美元',currency:'other',amount_text:'十五',amount_yuan:'15'}],text);
  assert.equal(result.drafts[0].amount_minor,250);
  assert.equal(result.drafts[1].amount_minor,null);
});

test('summary and change are not accepted as ordinary transactions',()=>{
  const text='总计十块五';
  const result=normalize([{...first,title:'总计',title_text:'总计',source_text:text,amount_text:'十块五',amount_yuan:'10.50'}],text);
  assert.equal(result.drafts[0].amount_minor,null);
  assert.equal(result.drafts[0].review_required,true);
});

test('correct recognition makes a single request and missing amounts remain explicitly missing',async()=>{
  let calls=0;
  const result=await recognizeGrounded(original,reference,[],async(review)=>{calls++;assert.equal(review,undefined);return{entries:[first,second]};});
  assert.equal(calls,1);assert.equal(result.drafts.length,2);
  const text='喝了一碗粥';
  const missing=normalize([{...second,source_text:text,amount_text:null,amount_yuan:null}],text).drafts[0];
  assert.equal(missing.amount_minor,null);assert.equal(missing.review_required,false);
});

test('a bad copied name is reviewed once and corrected without dropping either bill',async()=>{
  let calls=0;
  const result=await recognizeGrounded(original,reference,[],async(review)=>{
    calls++;
    if(!review)return{entries:[first,{...second,title:'保障餐',title_text:'保障餐'}]};
    assert.ok(review.problems.length);return{entries:[first,second]};
  });
  assert.equal(calls,2);assert.deepEqual(result.drafts.map(d=>d.title),['保障餐','粥']);
});

test('failed or ineffective review is bounded and keeps the original unsaved proposals',async()=>{
  for(const mode of ['network','same','drop']) {
    let calls=0;
    const wrong={entries:[first,{...second,title:'保障餐',title_text:'保障餐'}]};
    const result=await recognizeGrounded(original,reference,[],async(review)=>{
      calls++;if(!review)return wrong;
      if(mode==='network')throw new Error('timeout');
      return mode==='same'?wrong:{entries:[first]};
    });
    assert.equal(calls,2);assert.equal(result.drafts.length,2);assert.equal(result.drafts[1].title,'');
  }
});

test('structurally invalid model output receives one repair opportunity',async()=>{
  let calls=0;
  const result=await recognizeGrounded(original,reference,[],async(review)=>{calls++;return review?{entries:[first,second]}:{entries:[{...first,kind:'invalid'}]};});
  assert.equal(calls,2);assert.equal(result.drafts.length,2);
});

test('an empty result for an explicit payment gets one review instead of losing the bill',async()=>{
  const text='保障餐和粥一共花了十块五';let calls=0;
  const result=await recognizeGrounded(text,reference,[],async(review)=>{
    calls++;return review?{entries:[{...first,title:'保障餐和粥',title_text:'保障餐和粥',source_text:text,amount_text:'十块五',amount_yuan:'10.50'}]}:{entries:[]};
  });
  assert.equal(calls,2);assert.equal(result.drafts[0].amount_minor,1050);
});

test('non-ledger content does not get an unnecessary second request',async()=>{
  let calls=0;
  const result=await recognizeGrounded('今天天气不错',reference,[],async()=>{calls++;return{entries:[]};});
  assert.equal(calls,1);assert.equal(result.drafts.length,0);
});
