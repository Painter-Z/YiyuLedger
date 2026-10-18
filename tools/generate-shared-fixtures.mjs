// Golden results come from the shipped Harmony domain code; all inputs are synthetic.
import { registerHooks } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(specifier === './Ledger' ? './Ledger.ts' : specifier, context);
} });
const ledger = await import('../harmony/entry/src/main/ets/model/Ledger.ts');
const cloud = await import('../harmony/entry/src/main/ets/model/CloudParsing.ts');
const suggestions = await import('../harmony/entry/src/main/ets/model/CategorySuggestions.ts');
const base = {id:'fixture_1',kind:'expense',amountMinor:1500,currency:'CNY',categoryId:'food',title:'盖饭',occurredOn:'2026-10-10',timeHint:'中午',note:'',source:'manual',groupId:'',linkedEntryId:'',createdAt:1000,updatedAt:1000,deletedAt:0};
const custom = [{id:'custom_pet',name:'宠物',kind:'expense'}, {id:'custom_work',name:'咨询',kind:'income'}];
const entries = [base,{...base,id:'i',kind:'income',amountMinor:800000,categoryId:'salary'},
  {...base,id:'r',kind:'refund',amountMinor:1200,occurredOn:'2026-10-11',linkedEntryId:base.id},
  {...base,id:'t',kind:'transfer',amountMinor:50000,categoryId:'transfer'},
  {...base,id:'d',kind:'debt',amountMinor:90000,categoryId:'debt'},
  {...base,id:'old',amountMinor:20000,occurredOn:'2026-09-30'},
  {...base,id:'pet',amountMinor:2900,categoryId:'custom_pet',title:'猫粮'},
  {...base,id:'deleted',deletedAt:2000}];
const proposal = {kind:'expense',title:'盖饭',currency:'CNY',amount_text:'15元',amount_yuan:'15.00',category_id:'food',occurred_on:'2026-10-10',date_text:'',time_hint:'中午',note:''};
const recognitionCases = [
 ['ordinary','中午吃盖饭15元',{}], ['correction','午餐15元，不对是13元',{amount_text:'13元',amount_yuan:'13'}],
 ['substring','午餐115元',{}], ['negative','调整-15元',{}], ['missing','买了一本书',{amount_text:null,amount_yuan:null,category_id:'education'}],
 ['foreign','午餐15美元',{amount_text:'15',amount_yuan:'15',currency:undefined}],
 ['wrongAmount','午餐15元',{amount_yuan:'1500'}], ['yesterday','昨天午餐15元',{date_text:'昨天',occurred_on:'2025-01-01'}],
 ['invalidDate','2月30日午餐15元',{date_text:'2月30日',occurred_on:'2026-02-30'}],
 ['category','午餐15元',{kind:'income',category_id:'food'}],
 ['custom','猫粮15元',{category_id:'custom_pet'}]
].map(([name,text,changes])=> { const raw={entries:[{...proposal,...changes}]}; return {name,text,reference:'2026-10-10',raw,expected:cloud.normalizeCloudResult(raw,text,'2026-10-10',custom)}; });
const amounts=['0.29','15.5','00015.05','9999999999.99','0','-1','15.001','1e5','NaN','.5','15元',''];
const evidence=['0.29元','15元','十五元','15块5毛','十五元五角六分','两百零八元','一千二百三十四点五六元','一万二千元','人民币12.34元','十元半','十五块五','1.2万元','1.234万元','0元','-15元','15.555元','两千五元','一百二元','大概十五到二十元','15美元','1e4元','NaN',''];
const periods=['week','month','year'].flatMap(mode=>['2026-10-10','2027-01-01','2024-03-31'].flatMap(reference=>[-1,0,1].map(offset=>({mode,reference,offset,expected:ledger.periodFor(mode,reference,offset)}))));
const statistics=['week','month','year'].map(mode=>{const period=ledger.periodFor(mode,'2026-10-10');return {period,summary:ledger.summarize(entries,period),trends:ledger.trendPoints(entries,period),rankings:ledger.categoryTotals(entries,period,'expense',custom)};});
const document={generatedFrom:'Harmony 0.4.0 domain; synthetic data only',custom,entries,amounts:amounts.map(text=>({text,expected:ledger.parseMinor(text)})),
 evidence:evidence.map(text=>{try{return{text,expected:cloud.evidenceMinor(text)}}catch{return{text,expected:null}}}),periods,statistics,recognitionCases,
 backup:ledger.exportBackup(entries,'2026-10-11T00:00:00.000Z',custom),csv:ledger.exportCsv(entries,custom),
 suggestions:suggestions.suggestedCategories('expense','custom_pet','买猫粮15元','猫粮',entries,custom).map(it=>it.id)};
await mkdir(new URL('../shared/fixtures/',import.meta.url),{recursive:true});
await writeFile(new URL('../shared/fixtures/ledger-golden.json',import.meta.url),JSON.stringify(document,null,2)+'\n');
console.log('Shared synthetic golden fixtures generated from Harmony domain.');
