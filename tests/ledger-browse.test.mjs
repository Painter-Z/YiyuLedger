import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
registerHooks({ resolve(specifier, context, nextResolve) { return nextResolve(specifier === './Ledger' ? './Ledger.ts' : specifier, context); } });
const { browseLedger, ledgerDateScope, ledgerDayPage, ledgerDayLabel } = await import('../harmony/entry/src/main/ets/model/LedgerBrowse.ts');
const { summarize, periodFor, parseBackupDocument, exportBackup } = await import('../harmony/entry/src/main/ets/model/Ledger.ts');
const options = (overrides = {}) => ({ today: '2026-10-14', search: '', kind: 'all', categoryId: '', dateFilter: '', rangeStart: '', rangeEnd: '', ...overrides });
const entry = (overrides = {}) => ({ id: 'bill', kind: 'expense', amountMinor: 750, currency: 'CNY', categoryId: 'food', title: '早餐', occurredOn: '2026-10-14', timeHint: '', note: '', source: 'manual', groupId: '', linkedEntryId: '', createdAt: 1000, updatedAt: 1000, deletedAt: 0, ...overrides });

test('default includes three calendar days by bill date, excluding earlier and future records', () => {
  const entries = ['2026-10-15', '2026-10-14', '2026-10-13', '2026-10-12', '2026-10-11'].map((day, index) => entry({ id: 'day' + index, occurredOn: day, createdAt: 5000 - index }));
  const result = browseLedger(entries, options());
  assert.deepEqual(result.entries.map(e => e.occurredOn), ['2026-10-14', '2026-10-13', '2026-10-12']);
  assert.equal(result.scope.start, '2026-10-12');
  assert.deepEqual(browseLedger([entries.at(-1)], options()).entries, []);
  assert.equal(ledgerDayPage(browseLedger([], options()))[0].expenseMinor, 0);
});

test('recent dates cross months, years and leap days and advance with today', () => {
  for (const [today, start] of [['2027-01-01', '2026-12-30'], ['2024-03-01', '2024-02-28'], ['2026-03-01', '2026-02-27']]) {
    assert.equal(ledgerDateScope(options({ today })).start, start);
  }
  const entries = [entry({ occurredOn: '2026-10-12' }), entry({ id: 'new', occurredOn: '2026-10-15' })];
  assert.deepEqual(browseLedger(entries, options({ today: '2026-10-15' })).entries.map(e => e.id), ['new']);
  assert.equal(ledgerDayLabel('2026-10-13', '2026-10-14'), '昨天 · 10-13');
});

test('global keyword search finds older records, while clearing restores recent days', () => {
  const entries = [entry({ id: 'older', occurredOn: '2025-08-01' }), entry({ id: 'today', title: '晚饭' })];
  const result = browseLedger(entries, options({ search: '早餐' }));
  assert.equal(result.scope.mode, 'all');
  assert.deepEqual(result.entries.map(e => e.id), ['older']);
  assert.deepEqual(browseLedger(entries, options({ search: '  ' })).entries.map(e => e.id), ['today']);
  assert.deepEqual(ledgerDayPage(browseLedger(entries, options({ search: '不存在' }))), []);
});

test('selected day replaces previous statistics scope, keeps type and searches only that day', () => {
  const entries = [entry({ id: 'earlier', occurredOn: '2026-09-08' }), entry({ id: 'other', occurredOn: '2026-09-09' }),
    entry({ id: 'income', kind: 'income', categoryId: 'salary', occurredOn: '2026-09-08', title: '工资', amountMinor: 60000 })];
  const picked = options({ dateFilter: '2026-09-08', rangeStart: '2026-10-01', rangeEnd: '2026-10-31' });
  assert.deepEqual(browseLedger(entries, picked).entries.map(e => e.id), ['earlier', 'income']);
  assert.deepEqual(browseLedger(entries, { ...picked, kind: 'income' }).entries.map(e => e.id), ['income']);
  assert.deepEqual(browseLedger(entries, { ...picked, search: '早餐' }).entries.map(e => e.id), ['earlier']);
  assert.deepEqual(browseLedger(entries, { ...picked, search: '晚饭' }).entries, []);
  assert.equal(ledgerDayPage(browseLedger(entries, { ...picked, search: '晚饭' }))[0].expenseMinor, 750);
});

test('statistics month/category and period navigation are not restricted to the recent three days', () => {
  const entries = [entry({ id: 'first', occurredOn: '2026-10-01' }), entry(), entry({ id: 'sept', occurredOn: '2026-09-30' })];
  assert.deepEqual(browseLedger(entries, options({ dateFilter: '2026-10', rangeStart: '2026-10-01', rangeEnd: '2026-10-31' })).entries.map(e => e.id), ['bill', 'first']);
  assert.deepEqual(browseLedger(entries, options({ rangeStart: '2026-09-01', rangeEnd: '2026-09-30', categoryId: 'food', search: '早餐' })).entries.map(e => e.id), ['sept']);
  assert.throws(() => ledgerDateScope(options({ dateFilter: '2026-02-30' })));
  assert.throws(() => ledgerDateScope(options({ rangeStart: '2026-10-20', rangeEnd: '2026-10-01' })));
});

test('daily expense uses complete active spending, independent of keyword/type/category and refunds', () => {
  const entries = [entry(), entry({ id: 'print', categoryId: 'other', title: '打印', amountMinor: 500 }),
    ...['income', 'refund', 'transfer', 'debt'].map((kind, i) => entry({ id: 'excluded' + i, kind, amountMinor: 3000 })),
    entry({ id: 'deleted', deletedAt: 2000 }), entry({ id: 'foreign', currency: 'USD' })];
  for (const extra of [{}, { search: '早餐' }, { kind: 'income' }, { categoryId: 'food' }]) {
    const result = browseLedger(entries, options(extra));
    assert.equal(ledgerDayPage(result)[0].expenseMinor, 1250);
    assert.ok(!result.entries.some(e => e.id === 'deleted' || e.id === 'foreign'));
  }
  assert.equal(ledgerDayPage(browseLedger(entries.filter(e => e.id !== 'print'), options()))[0].expenseMinor, 750);
  assert.equal(ledgerDayPage(browseLedger([entry({ amountMinor: 1 }), entry({ id: 'cent', amountMinor: 29 })], options()))[0].expenseMinor, 30);
});

test('paged rendering preserves full day totals, correct group order, and empty selected-day headings', () => {
  const entries = Array.from({ length: 240 }, (_, i) => entry({ id: 'many' + i, amountMinor: 1, createdAt: i + 1 }));
  const result = browseLedger(entries, options());
  assert.equal(result.entries.length, 240);
  assert.equal(ledgerDayPage(result, 80)[0].entries.length, 80);
  assert.equal(ledgerDayPage(result, 80)[0].expenseMinor, 240);
  assert.equal(ledgerDayPage(result, 160)[0].entries.length, 160);
  assert.equal(ledgerDayPage(result, 320)[0].entries.length, 240);
  const empty = ledgerDayPage(browseLedger(entries, options({ dateFilter: '2026-01-01' })));
  assert.equal(empty.length, 1); assert.equal(empty[0].date, '2026-01-01'); assert.equal(empty[0].expenseMinor, 0);
});

test('browsing never truncates the stored book, monthly statistics or complete backup', () => {
  const entries = [entry({ id: 'older', occurredOn: '2026-10-01', amountMinor: 1000 }), entry()];
  const before = JSON.stringify(entries);
  assert.equal(browseLedger(entries, options()).entries.length, 1);
  assert.equal(JSON.stringify(entries), before);
  assert.equal(summarize(entries, periodFor('month', '2026-10-14')).expense, 1750);
  assert.equal(parseBackupDocument(exportBackup(entries)).entries.length, 2);
});
