// Freeze the shipped Harmony 0.7.0 recognition behavior for Android parity checks.
import {registerHooks} from 'node:module';
import {readFile,writeFile} from 'node:fs/promises';
registerHooks({resolve(specifier,context,next) {
  return next(context.parentURL?.includes('/model/')&&/^\.\/\w+$/.test(specifier)?specifier+'.ts':specifier,context);
}});
const {normalizeCloudResult,evidenceMinor}=await import('../harmony/entry/src/main/ets/model/CloudParsing.ts');
const previous=JSON.parse(await readFile(new URL('../shared/fixtures/ledger-golden.json',import.meta.url),'utf8'));
const reference='2026-10-18';
const original='今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五';
const first={kind:'expense',currency:'CNY',title:'保障餐',title_text:'保障餐',source_text:'今天我吃了保障餐，花了八块',amount_text:'八块',amount_yuan:'8.00',category_id:'food'};
const second={...first,title:'粥',title_text:'粥',source_text:'又喝了一碗粥，花了两块五',amount_text:'两块五',amount_yuan:'2.50'};
const inputs=previous.recognitionCases.map(item=>({...item,name:'previous-'+item.name,raw:{entries:item.raw.entries.map(entry=>({source_text:item.text,title_text:entry.title,...entry}))}}));
function add(name,text,entries){inputs.push({name,text,reference,raw:{entries}});}
add('reported-meal-and-porridge',original,[first,second]);
add('copied-title-own-anchor',original,[first,{...second,title:'保障餐'}]);
add('copied-title-and-anchor',original,[first,{...second,title:'保障餐',title_text:'保障餐'}]);
add('swapped-amounts',original,[{...first,amount_text:'两块五',amount_yuan:'2.50'},{...second,amount_text:'八块',amount_yuan:'8.00'}]);
add('reverse-model-order',original,[second,first]);
add('overwide-quotes',original,[{...first,source_text:original},{...second,source_text:original}]);
add('unsafe-overlap',original,[first,{...second,title:'保障餐',title_text:'保障餐',source_text:original}]);
add('reused-source',original,[first,{...second,source_text:first.source_text}]);
for(const text of ['保障餐八块。保障餐八块','早上保障餐八块，晚上保障餐八块']) {
  add('same-name-'+inputs.length,text,[{...first,source_text:'保障餐八块'},{...first,source_text:'保障餐八块'}]);
}
const repeated='早上保障餐八块，晚上保障餐八块';
add('same-name-overwide',repeated,[{...first,source_text:repeated},{...first,source_text:repeated}]);
for(const text of ['保障餐十一块八毛九','保障餐11块8毛9','保障餐十一元八角九']) {
  add('spoken-cents-'+inputs.length,text,[{...first,source_text:text,amount_text:text.slice(3),amount_yuan:'11.89'}]);
}
for(const [text,evidence,yuan] of [['保障餐十一块','一块','1'],['保障餐十一块八毛九','十一块','11'],['保障餐十一块八毛九','八毛九','0.89'],['保障餐负十一块','十一块','11']])
  add('truncated-or-negative-'+inputs.length,text,[{...first,source_text:text,amount_text:evidence,amount_yuan:yuan}]);
const correction='保障餐八块，不对应该是七块五';
add('oral-correction-wrong',correction,[{...first,source_text:correction}]);
add('oral-correction-final',correction,[{...first,source_text:correction,amount_text:'七块五',amount_yuan:'7.50'}]);
for(const text of ['用美元买书十五元，喝粥两块五','买书十五美元，喝粥两块五'])
  add('foreign-overwide-'+inputs.length,text,[{...first,title:'书',title_text:'书',source_text:text,amount_text:'十五',amount_yuan:'15'},{...second,source_text:text}]);
add('foreign-next-transaction','粥两块五，书十五美元',[{...second,source_text:'粥两块五'},{...first,title:'书',title_text:'书',source_text:'书十五美元',currency:'other',amount_text:'十五',amount_yuan:'15'}]);
add('missing-amount','今天买了一碗粥，价格忘了',[{...second,source_text:'今天买了一碗粥，价格忘了',amount_text:null,amount_yuan:null}]);
add('combined-payment','保障餐和粥一共花了十块五',[{...first,title:'保障餐和粥',title_text:'保障餐和粥',source_text:'保障餐和粥一共花了十块五',amount_text:'十块五',amount_yuan:'10.50'}]);
add('summary-not-a-payment','总计十块五',[{...first,title:'总计',title_text:'总计',source_text:'总计十块五',amount_text:'十块五',amount_yuan:'10.50'}]);
add('nonliteral-source',original,[{...first,source_text:'今天我吃了保障餐，花了8块'},second]);
add('no-ledger-content','今天天气不错',[]);
const cases=inputs.map(item=>({...item,expected:normalizeCloudResult(item.raw,item.text,item.reference,previous.custom)}));
const texts=[...previous.evidence.map(item=>item.text),'十一块八毛九','11块8毛9','十一元八角九','11块八毛9','两块五','十一块零毛九分','十一块零毛九','八毛九','8角9分','五毛','九分','零元八角九分','十一元零九分','0元05分','十一二块','十一块八九','十一块八毛九厘','十一块八毛九分一厘','零毛零','1元10角','十一块八毛九分五'];
const evidence=[...new Set(texts)].map(text=>{try{return{text,expected:evidenceMinor(text)}}catch{return{text,expected:null}}});
const document={version:2,generatedFrom:'Shipped Harmony 0.7.0; reported food example and synthetic data only',custom:previous.custom,evidence,cases};
await writeFile(new URL('../shared/fixtures/recognition-v2.json',import.meta.url),JSON.stringify(document,null,2)+'\n');
console.log(`Recognition v2 contract: ${cases.length} proposal cases and ${evidence.length} spoken amount cases.`);
