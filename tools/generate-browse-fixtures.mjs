// Cross-platform contract generated from the updated Harmony code, with synthetic bills only.
import { registerHooks } from 'node:module';
import { writeFile } from 'node:fs/promises';
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(specifier === './Ledger' ? './Ledger.ts' : specifier, context);
} });
const { browseLedger, ledgerDayPage, ledgerDayLabel } = await import('../harmony/entry/src/main/ets/model/LedgerBrowse.ts');
const base = { id:'today_food', kind:'expense', amountMinor:750, currency:'CNY', categoryId:'food', title:'早餐', occurredOn:'2026-10-14',
  timeHint:'', note:'', source:'manual', groupId:'', linkedEntryId:'', createdAt:1000, updatedAt:1000, deletedAt:0 };
const entries = [base, { ...base, id:'today_print', categoryId:'other', title:'打印', amountMinor:500 },
  { ...base, id:'today_income', kind:'income', categoryId:'salary', title:'工资', amountMinor:60000 },
  { ...base, id:'today_refund', kind:'refund', title:'早餐退款', amountMinor:250 },
  { ...base, id:'today_transfer', kind:'transfer', categoryId:'transfer', title:'转账', amountMinor:20000 },
  { ...base, id:'today_debt', kind:'debt', categoryId:'debt', title:'借款', amountMinor:20000 },
  { ...base, id:'yesterday', occurredOn:'2026-10-13' }, { ...base, id:'day_before', occurredOn:'2026-10-12' },
  { ...base, id:'earlier_month', occurredOn:'2026-10-01' }, { ...base, id:'history', occurredOn:'2025-08-01' },
  { ...base, id:'future', occurredOn:'2026-10-15' }, { ...base, id:'deleted', deletedAt:2000 }, { ...base, id:'foreign', currency:'USD' }];
const options = overrides => ({ today:'2026-10-14', search:'', kind:'all', categoryId:'', dateFilter:'', rangeStart:'', rangeEnd:'', ...overrides });
const definitions = [['recent', {}], ['search_history', {search:'早餐'}], ['clear_search', {search:'  '}], ['no_history_match', {search:'不存在'}],
  ['only_income', {kind:'income'}], ['only_refund', {kind:'refund'}], ['only_transfer', {kind:'transfer'}], ['only_debt', {kind:'debt'}],
  ['food_filter', {categoryId:'food'}], ['empty_day', {dateFilter:'2026-01-01'}], ['empty_day_search', {dateFilter:'2026-10-14',search:'不存在'}],
  ['selected_day', {dateFilter:'2025-08-01',rangeStart:'2026-10-01',rangeEnd:'2026-10-31'}], ['selected_income', {dateFilter:'2026-10-14',kind:'income'}],
  ['month', {dateFilter:'2026-10'}], ['stats_category_period', {rangeStart:'2026-10-01',rangeEnd:'2026-10-31',categoryId:'food'}],
  ['range_search', {rangeStart:'2025-01-01',rangeEnd:'2025-12-31',search:'早餐'}], ['midnight', {today:'2026-10-15'}],
  ['cross_year', {today:'2027-01-01'}], ['leap_day', {today:'2024-03-01'}], ['non_leap_day', {today:'2026-03-01'}]];
const cases = definitions.map(([name, overrides]) => {
  const input = options(overrides); const result = browseLedger(entries, input);
  return { name, options:input, expected:{ scope:result.scope, entryIds:result.entries.map(e => e.id), expenseByDay:Object.fromEntries(result.expenseByDay),
    emptyDay:result.emptyDay, days:ledgerDayPage(result).map(day => ({ date:day.date, expenseMinor:day.expenseMinor,
      label:ledgerDayLabel(day.date,input.today), entryIds:day.entries.map(e => e.id) })) } };
});
await writeFile(new URL('../shared/fixtures/ledger-browse.json', import.meta.url), JSON.stringify({ generatedFrom:'Harmony 0.6.1 LedgerBrowse; synthetic data', entries, cases }, null, 2)+'\n');
console.log(`Generated ${cases.length} Harmony browsing contract cases.`);
