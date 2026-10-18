import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
registerHooks({resolve(specifier,context,nextResolve) { return nextResolve(specifier === './Ledger' ? './Ledger.ts' : specifier,context); }});
const { budgetLimit, monthlyBudgetProgress, budgetMonthFor } = await import('../harmony/entry/src/main/ets/model/Budget.ts');
const { validateMonthlyBudgets, mergeMonthlyBudgets, parseBackupDocument, exportBackup, periodFor } = await import('../harmony/entry/src/main/ets/model/Ledger.ts');
const fixture=JSON.parse(readFileSync(new URL('../shared/fixtures/monthly-budget.json',import.meta.url),'utf8'));
const entry=(overrides={})=>({id:'budget_test',kind:'expense',amountMinor:900,currency:'CNY',categoryId:'food',title:'米线',occurredOn:'2026-10-12',timeHint:'',note:'',source:'manual',groupId:'',linkedEntryId:'',createdAt:1000,updatedAt:1000,deletedAt:0,...overrides});

test('shared monthly-budget scenarios cover empty, exact, fractional and multi-lap overspending plus refunds',()=>{
  for(const item of fixture.cases) {
    const entries=[];
    if(item.expense) entries.push(entry({amountMinor:item.expense}));
    if(item.refund) entries.push(entry({id:'refund',kind:'refund',amountMinor:item.refund}));
    const result=monthlyBudgetProgress(entries,[{month:'2026-10',amountMinor:item.limit}],'2026-10');
    for(const key of ['state','spent','remaining','overspent']) assert.equal(result[key],item[key],`${item.name}/${key}`);
    for(const key of ['baseRatio','overRatio']) assert.ok(Math.abs(result[key]-item[key])<1e-10,`${item.name}/${key}`);
  }
});
test('income, transfer, debt, deleted and out-of-month bills never consume a monthly budget',()=>{
  const entries=[entry(),...['income','transfer','debt'].map((kind,index)=>entry({id:'ignored'+index,kind,amountMinor:80000})),
    entry({id:'deleted',deletedAt:2000,amountMinor:50000}),entry({id:'prior',occurredOn:'2026-09-30',amountMinor:10000})];
  assert.equal(monthlyBudgetProgress(entries,[{month:'2026-10',amountMinor:10000}],'2026-10').spent,900);
  assert.equal(monthlyBudgetProgress(entries.filter(e=>e.id!=='budget_test'),[{month:'2026-10',amountMinor:10000}],'2026-10').spent,0);
});
test('later months inherit the latest earlier choice, including an explicit disabled marker',()=>{
  const budgets=[{month:'2026-12',amountMinor:0},{month:'2026-10',amountMinor:20000},{month:'2027-02',amountMinor:35000}];
  assert.equal(budgetLimit(budgets,'2026-09'),0); assert.equal(budgetLimit(budgets,'2026-11'),20000);
  assert.equal(budgetLimit(budgets,'2027-01'),0); assert.equal(budgetLimit(budgets,'2027-03'),35000);
});
test('month view follows selection while weekly and yearly views show the dated current-month budget',()=>{
  const period=periodFor('month','2026-10-12',-1);
  assert.equal(budgetMonthFor('month',period,'2026-10-12'),'2026-09');
  for(const mode of ['week','year']) assert.equal(budgetMonthFor(mode,period,'2026-10-12'),'2026-10');
});
test('invalid or duplicate budget settings and malformed v3 imports are rejected before merging',()=>{
  for(const budgets of [undefined,[{month:'2026-13',amountMinor:1}],[{month:'1899-12',amountMinor:1}],
    [{month:'2026-10',amountMinor:-1}],[{month:'2026-10',amountMinor:1.5}],[{month:'2026-10',amountMinor:'100'}],
    [{month:'2026-10',amountMinor:1e12}],[{month:'2026-10',amountMinor:10},{month:'2026-10',amountMinor:20}]]) {
    assert.notEqual(validateMonthlyBudgets(budgets),'');
    assert.throws(()=>parseBackupDocument(JSON.stringify({format:'yiyu-ledger',version:3,categories:[],entries:[],monthlyBudgets:budgets})));
  }
});
test('v3 roundtrip preserves monthly limits and disabled markers, with v1/v2 import compatibility',()=>{
  const budgets=[{month:'2026-10',amountMinor:10000},{month:'2026-11',amountMinor:0}];
  const saved=parseBackupDocument(exportBackup([entry()],undefined,[],budgets));
  assert.equal(saved.version,3); assert.deepEqual(saved.monthlyBudgets,budgets);
  for(const version of [1,2]) assert.deepEqual(parseBackupDocument(JSON.stringify({format:'yiyu-ledger',version,entries:[entry()],categories:[]})).monthlyBudgets,[]);
  assert.deepEqual(mergeMonthlyBudgets(budgets,[{month:'2026-10',amountMinor:999},{month:'2026-12',amountMinor:15000}]),
    [...budgets,{month:'2026-12',amountMinor:15000}]);
});
