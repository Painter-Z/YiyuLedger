// Opt-in real API check. Only synthetic text and the user's reported food example are sent.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {registerHooks} from 'node:module';
import path from 'node:path';
import assert from 'node:assert/strict';

if(!process.argv.includes('--live')) {
  console.log('Use --live to verify Harmony extraction with the existing backend/.env key. No bills are saved.');
  process.exit(0);
}
registerHooks({resolve(specifier,context,next) {
  return next(context.parentURL?.includes('/model/')&&/^\.\/\w+$/.test(specifier)?specifier+'.ts':specifier,context);
}});
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const {SYSTEM_PROMPT}=await import('../harmony/entry/src/main/ets/model/CloudPrompt.ts');
const {recognizeGrounded}=await import('../harmony/entry/src/main/ets/model/CloudRecognition.ts');
const values=(await readFile(path.join(root,'backend/.env'),'utf8')).replace(/^\uFEFF/,'').split(/\r?\n/)
  .filter(line=>/^DASHSCOPE_API_KEY=/.test(line));
const key=(values.at(-1)?.slice('DASHSCOPE_API_KEY='.length)||'').trim().replace(/^(['"])(.*)\1$/,'$2');
if(!key) throw new Error('Configure DASHSCOPE_API_KEY in backend/.env without putting it in source or chat.');
const reference='2026-10-18';
let cases=[
  {name:'reported-meal-and-porridge',text:'今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五',expected:[['保障餐',800,'expense'],['粥',250,'expense']]},
  {name:'colloquial-cents',text:'中午吃了一碗牛肉面，花了十一块八毛九',expected:[['牛肉面',1189,'expense']]},
  {name:'same-name-real-purchases',text:'早上吃保障餐八块，晚上又吃保障餐八块',expected:[['保障餐',800,'expense'],['保障餐',800,'expense']]},
  {name:'same-price-different-items',text:'喝了一碗粥两块五，又买了豆浆两块五',expected:[['粥',250,'expense'],['豆浆',250,'expense']]},
  {name:'oral-correction',text:'保障餐花了八块，不对应该是七块五',expected:[['保障餐',750,'expense']]},
  {name:'summary-not-duplicated',text:'牛肉面十五元，地铁两元，总共十七元',expected:[['牛肉面',1500,'expense'],['地铁',200,'expense']]},
  {name:'fractional-only',text:'买了一个包子花了八毛九',expected:[['包子',89,'expense']]},
  {name:'combined-total-not-split',text:'保障餐和粥一共花了十块五',expected:[['保障餐和粥',1050,'expense']]},
  {name:'missing-price-not-guessed',text:'帮我记一笔，今天买了一碗粥，价格忘了',expected:[['粥',null,'expense']]},
  {name:'non-ledger-content',text:'今天天气不错，下午准备去散步',expected:[]}
];
if(process.argv.includes('--speech')) {
  const speech=JSON.parse(await readFile(path.join(root,'artifacts/harmony-v070-speech-probe.json'),'utf8'));
  assert.equal(speech.synthetic_only,true);
  cases=speech.cases.map(item=>({...item,text:item.transcript}));
}
const results=[];
for(const item of cases) {
  const selected=process.argv.filter(arg=>arg.startsWith('--case=')).map(arg=>arg.slice(7));
  if(selected.length&&!selected.includes(item.name)) continue;
  let calls=0;
  let observed;
  const responses=[];
  try {
    const result=await recognizeGrounded(item.text,reference,[],async(review)=>{
      calls++;
      const content={reference_date:reference,text:item.text,custom_categories:[],...(review?{review}:{})};
      const response=await fetch('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',{
        method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+key},
        body:JSON.stringify({model:'qwen-plus',temperature:0,response_format:{type:'json_object'},messages:[{role:'system',content:SYSTEM_PROMPT},{role:'user',content:JSON.stringify(content)}]}),
        signal:AbortSignal.timeout(review?15000:45000)
      });
      if(!response.ok) throw new Error('Cloud request HTTP '+response.status);
      const data=await response.json();
      if(data.choices?.[0]?.finish_reason==='length') throw new Error('Incomplete result');
      const proposal=JSON.parse(data.choices[0].message.content);
      responses.push(proposal);
      return proposal;
    });
    observed=result;
    assert.deepEqual(result.drafts.map(d=>[d.title,d.amount_minor,d.kind]),item.expected);
    assert.ok(result.drafts.every(d=>d.review_required!==true));
    results.push({name:item.name,passed:true,calls,result});
    console.log('PASS '+item.name+' ('+calls+' request'+(calls===1?'':'s')+')');
  } catch(error) {
    // Never echo server bodies, request headers, env content, or arbitrary exception messages.
    results.push({name:item.name,passed:false,calls,error_type:error.name||'Error',observed,responses});
    console.log('FAIL '+item.name+' ('+calls+' requests; '+(error.name||'Error')+')');
  }
}
await writeFile(path.join(root,'artifacts/'+(process.argv.includes('--speech')?'harmony-v070-speech-extraction.json':'harmony-v070-cloud-probe.json')),JSON.stringify({reference_date:reference,model:'qwen-plus',bill_writes:0,cases:results},null,2)+'\n');
console.log(results.filter(r=>r.passed).length+'/'+results.length+' real cloud cases passed. No bills saved.');
if(results.some(r=>!r.passed)) process.exitCode=1;
