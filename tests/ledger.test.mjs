import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMinor, formatMoney, decimalAmount, isDateKey, periodFor, summarize, categoryTotals, trendPoints, seedEntries, validateEntry, exportBackup, parseBackup, exportCsv, filterEntries } from '../harmony/entry/src/main/ets/model/Ledger.ts';

const base = () => ({ id: 'test_1', kind: 'expense', amountMinor: 15_00, currency: 'CNY', categoryId: 'food', title: '盖饭', occurredOn: '2026-10-10', timeHint: '中午', note: '', source: 'manual', groupId: '', linkedEntryId: '', createdAt: 1000, updatedAt: 1000, deletedAt: 0 });
const month = periodFor('month', '2026-10-10');

test('decimal input never loses cents to floating point', () => {
  assert.equal(parseMinor('0.29'), 29);
  assert.equal(parseMinor('15.5'), 1550);
  assert.equal(parseMinor('00015.05'), 1505);
  for (const bad of ['0', '-15', '15.001', '1e5', 'NaN', 'Infinity', '1,000', '.5', '15元', '']) assert.equal(parseMinor(bad), -1, bad);
});
test('money formats refunds and negative net totals exactly', () => {
  assert.equal(formatMoney(128650), '1,286.50');
  assert.equal(formatMoney(-1), '-0.01');
  assert.equal(decimalAmount(29), '0.29');
});
test('real calendar validation rejects rollover dates', () => {
  assert.equal(isDateKey('2024-02-29'), true);
  for (const day of ['2026-02-29', '2026-04-31', '2026-13-01', '2026-1-01', '2026-00-01']) assert.equal(isDateKey(day), false);
});
test('weeks start Monday and correctly cross the year boundary', () => {
  const p = periodFor('week', '2027-01-01');
  assert.equal(p.start, '2026-12-28');
  assert.equal(p.end, '2027-01-03');
});
test('month navigation handles the 31st and leap years', () => {
  assert.equal(periodFor('month', '2024-03-31', -1).end, '2024-02-29');
  assert.equal(periodFor('month', '2026-01-31', -1).start, '2025-12-01');
  assert.equal(periodFor('year', '2026-10-10', -1).end, '2025-12-31');
});
test('transfers and debt are excluded from income and expenses', () => {
  const entries = [base(), { ...base(), id: 'i', kind: 'income', categoryId: 'salary', amountMinor: 800000 }, { ...base(), id: 't', kind: 'transfer', categoryId: 'transfer', amountMinor: 50000 }, { ...base(), id: 'd', kind: 'debt', categoryId: 'debt', amountMinor: 20000 }];
  const totals = summarize(entries, month);
  assert.equal(totals.expense, 1500);
  assert.equal(totals.income, 800000);
  assert.equal(totals.balance, 798500);
});
test('cross-month refunds appear in the refund month without rewriting the purchase', () => {
  const entries = [{ ...base(), occurredOn: '2026-09-30', amountMinor: 20000 }, { ...base(), id: 'r', kind: 'refund', occurredOn: '2026-10-01', amountMinor: 10000, linkedEntryId: 'test_1' }];
  assert.equal(summarize(entries, month).netExpense, -10000);
  assert.equal(summarize(entries, periodFor('month', '2026-09-10')).expense, 20000);
});
test('deleted, foreign currency and out-of-period entries never leak into totals', () => {
  const entries = [base(), { ...base(), id: 'x', deletedAt: 1001 }, { ...base(), id: 'usd', currency: 'USD' }, { ...base(), id: 'old', occurredOn: '2026-09-10' }];
  assert.equal(summarize(entries, month).expense, 1500);
});
test('category shares are finite with zero and nonzero totals', () => {
  assert.deepEqual(categoryTotals([], month), []);
  const totals = categoryTotals(seedEntries('2026-10-10'), month);
  assert.equal(totals[0].category.id, 'housing');
  assert.ok(Math.abs(totals.reduce((sum, item) => sum + item.ratio, 0) - 1) < 1e-10);
});
test('all chart buckets sum to the same ledger totals', () => {
  const entries = seedEntries('2026-10-10');
  for (const mode of ['week', 'month', 'year']) {
    const period = periodFor(mode, '2026-10-10');
    const points = trendPoints(entries, period);
    assert.equal(points.reduce((sum, point) => sum + point.expense, 0), summarize(entries, period).expense);
    assert.equal(points.reduce((sum, point) => sum + point.income, 0), summarize(entries, period).income);
  }
});
test('demo data is labeled at source and its hero amount is calculated', () => {
  const entries = seedEntries('2026-10-10');
  assert.equal(summarize(entries, month).expense, 128650);
  assert.equal(summarize(entries, month).income, 800000);
  assert.ok(entries.every(entry => entry.source === 'sample' && validateEntry(entry) === ''));
});
test('entry validation rejects invalid kind/category pairs and noninteger amounts', () => {
  assert.equal(validateEntry(base()), '');
  assert.notEqual(validateEntry({ ...base(), amountMinor: 0.1 }), '');
  assert.notEqual(validateEntry({ ...base(), kind: 'income' }), '');
  assert.notEqual(validateEntry({ ...base(), title: '  ' }), '');
  assert.notEqual(validateEntry({ ...base(), currency: 'USD' }), '');
});
test('backup round trip preserves cents and refund links, while deleted rows are excluded', () => {
  const entries = [base(), { ...base(), id: 'r', kind: 'refund', linkedEntryId: 'test_1' }];
  assert.deepEqual(parseBackup(exportBackup(entries)), entries);
  assert.deepEqual(parseBackup(exportBackup([...entries, {...base(), id:'deleted', deletedAt:1200}])), entries);
  assert.deepEqual(parseBackup(JSON.stringify({format:'yiyu-ledger',version:1,entries:[...entries,{...base(),id:'deleted',deletedAt:1200}]})),entries);
});
test('backup validation is all or nothing and rejects duplicate ids', () => {
  assert.throws(() => parseBackup('{"format":"wrong","version":1,"entries":[]}'));
  assert.throws(() => parseBackup(exportBackup([base(), base()])));
  assert.throws(() => parseBackup(exportBackup([{ ...base(), amountMinor: -1 }])));
  assert.throws(() => parseBackup('null'));
});
test('CSV escapes quotes and spreadsheet formulas', () => {
  const csv = exportCsv([{ ...base(), title: '=HYPERLINK("bad")', note: '一,二\n三' }]);
  assert.ok(csv.startsWith('\uFEFF'));
  assert.ok(csv.includes("'="));
  assert.ok(csv.includes('""bad""'));
});
test('search and category drill-down retain independent same-amount entries', () => {
  const entries = [base(), { ...base(), id: 'test_2' }];
  assert.equal(filterEntries(entries, '15.00', 'expense', 'food').length, 2);
  assert.equal(filterEntries(entries, '餐饮').length, 2);
  assert.equal(filterEntries(entries, '不存在').length, 0);
});
