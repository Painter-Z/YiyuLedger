import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
registerHooks({ resolve(specifier, context, nextResolve) {
  return nextResolve(specifier === './Ledger' ? './Ledger.ts' : specifier, context);
} });
const { categoriesForKind, validateCategoryCatalog, validateEntry, exportCsv, exportBackup,
  parseBackupDocument, mergeCategoryCatalog, filterEntries, categoryTotals, periodFor } = await import('../harmony/entry/src/main/ets/model/Ledger.ts');
const { suggestedCategories } = await import('../harmony/entry/src/main/ets/model/CategorySuggestions.ts');
const { normalizeCloudResult } = await import('../harmony/entry/src/main/ets/model/CloudParsing.ts');
const { ledgerLayout } = await import('../harmony/entry/src/main/ets/model/ResponsiveLayout.ts');
const custom = [{ id: 'custom_pet', name: '宠物', kind: 'expense' }, { id: 'custom_rent', name: '租金', kind: 'income' }];
const entry = (overrides = {}) => ({ id: 'cat_1', kind: 'expense', amountMinor: 1500, currency: 'CNY', categoryId: 'custom_pet', title: '猫粮', occurredOn: '2026-10-11', timeHint: '', note: '', source: 'manual', groupId: '', linkedEntryId: '', createdAt: 1000, updatedAt: 1000, deletedAt: 0, ...overrides });

test('custom categories remain scoped to accounting direction and refunds share expense labels', () => {
  assert.equal(validateCategoryCatalog(custom), '');
  assert.equal(validateEntry(entry(), custom), '');
  assert.equal(validateEntry(entry({kind: 'refund'}), custom), '');
  assert.notEqual(validateEntry(entry({kind: 'income'}), custom), '');
  assert.notEqual(validateEntry(entry(), []), '');
  assert.equal(categoriesForKind('income', custom).some(c => c.id === 'custom_pet'), false);
});

test('category names reject duplicates, control characters, spoofed built-in ids and oversized catalogs', () => {
  for (const bad of [
    [...custom, {...custom[0]}], [{...custom[0], id: 'food'}], [{...custom[0], name: '餐饮'}],
    [{...custom[0], name: ' 宠物 '}], [{...custom[0], name: '宠\n物'}], [{...custom[0], name: 'a'.repeat(13)}],
    [{...custom[0], name: ''}], [{...custom[0], kind: 'refund'}], Array(101).fill(custom[0]), undefined
  ]) assert.notEqual(validateCategoryCatalog(bad), '');
});

test('custom names participate in search, category drill-down, totals and CSV', () => {
  const entries = [entry(), entry({id: 'deleted', amountMinor: 9000, deletedAt: 1100}), entry({id: 'food', categoryId: 'food', amountMinor: 500})];
  assert.equal(filterEntries(entries, '宠物', 'all', '', custom).length, 1);
  assert.equal(filterEntries(entries, '', 'expense', 'custom_pet', custom)[0].title, '猫粮');
  const totals = categoryTotals(entries, periodFor('month', '2026-10-11'), 'expense', custom);
  assert.equal(totals[0].category.name, '宠物');
  assert.equal(totals[0].ratio, 0.75);
  assert.equal(totals.reduce((sum, item) => sum + item.amount, 0), 2000);
  assert.ok(exportCsv(entries, custom).includes('"宠物"'));
});

test('v2 backup keeps unused categories and v1 backups still import', () => {
  const restored = parseBackupDocument(exportBackup([entry()], '2026-10-11T00:00:00Z', custom));
  assert.deepEqual(restored.categories, custom);
  assert.deepEqual(restored.entries, [entry()]);
  const old = JSON.stringify({format:'yiyu-ledger',version:1,exportedAt:'2026-10-11',entries:[entry({categoryId:'food'})]});
  assert.deepEqual(parseBackupDocument(old).categories, []);
  assert.throws(() => parseBackupDocument(exportBackup([entry()], undefined, [])));
  assert.throws(() => parseBackupDocument(exportBackup([entry()], undefined, [...custom, custom[0]])));
});

test('backup merging never changes the meaning of an existing category identifier', () => {
  assert.deepEqual(mergeCategoryCatalog(custom, [custom[0]]), custom);
  assert.throws(() => mergeCategoryCatalog(custom, [{...custom[0], name:'健身'}]));
  assert.throws(() => mergeCategoryCatalog(custom, [{...custom[0], kind:'income'}]));
});

test('recommendations retain the chosen category and use semantic context and prior corrections', () => {
  const result = suggestedCategories('expense', 'other', '中午吃米线9元', '米线', [], custom);
  assert.equal(result[0].id, 'other');
  assert.equal(result[1].id, 'food');
  const learned = suggestedCategories('expense', 'shopping', '买猫粮15元', '猫粮', [entry()], custom);
  assert.equal(learned[0].id, 'shopping');
  assert.equal(learned[1].id, 'custom_pet');
  assert.equal(new Set(learned.map(c => c.id)).size, learned.length);
  assert.ok(suggestedCategories('income','salary','猫粮','猫粮',[entry()],custom).every(c => c.id !== 'custom_pet'));
});

test('cloud custom labels are accepted only from the supplied catalog and matching kind', () => {
  const proposal = {kind:'expense',currency:'CNY',title:'猫粮',title_text:'猫粮',source_text:'猫粮15元',amount_text:'15元',amount_yuan:'15.00',category_id:'custom_pet',date_text:''};
  const parsed = normalizeCloudResult({entries:[proposal]}, '猫粮15元', '2026-10-11', custom);
  assert.equal(parsed.drafts[0].category_id, 'custom_pet');
  assert.equal(parsed.drafts[0].amount_minor, 1500);
  assert.notEqual(normalizeCloudResult({entries:[proposal]}, '猫粮15元', '2026-10-11').drafts[0].category_id, 'custom_pet');
  assert.notEqual(normalizeCloudResult({entries:[{...proposal,kind:'income'}]}, '猫粮15元', '2026-10-11', custom).drafts[0].category_id, 'custom_pet');
});

test('narrow windows and enlarged fonts reduce chips without shrinking touch targets', () => {
  for (const width of [280,320,360,390,412,600,840,1280]) {
    let previous = 3;
    for (const scale of [1,1.15,1.3,1.75,2,3.2]) {
      const layout = ledgerLayout(width, scale);
      assert.ok(layout.pageWidth <= width && layout.sheetWidth <= width);
      assert.ok(layout.sheetWidth <= 600 && layout.pageWidth <= 720);
      assert.ok(layout.quickCount >= 1 && layout.quickCount <= previous);
      assert.ok(layout.categoryColumns >= 2 && layout.categoryColumns <= 5);
      assert.ok(layout.controlHeight >= 48 && layout.controlHeight >= 22 * scale + 20);
      const chipWidth = (layout.sheetWidth - layout.padding * 2 - layout.quickCount * 6) / (layout.quickCount + 1);
      assert.ok(chipWidth >= 48, `${width}vp/${scale}: touch target ${chipWidth}`);
      previous = layout.quickCount;
    }
  }
});
