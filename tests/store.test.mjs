// Exercise the actual native store against SQLite. Only Harmony's RDB/filesystem bindings are adapted.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { registerHooks, stripTypeScriptTypes } from 'node:module';

const databases = new Map();
const handles = [];
globalThis.__yiyuRdbTest = { async getRdbStore(context) {
  const file = path.join(context.filesDir, 'YiyuLedger.db');
  const db = new DatabaseSync(file); databases.set(file, db); handles.push(db);
  return {
    async executeSql(sql, parameters = []) { db.prepare(sql).run(...parameters); },
    async querySql(sql, parameters = []) {
      const rows = db.prepare(sql).all(...parameters); let index = -1;
      return { goToNextRow() { return ++index < rows.length; }, getString(column) { return Object.values(rows[index])[column]; }, close() {} };
    },
    beginTransaction() { db.exec('BEGIN'); }, commit() { db.exec('COMMIT'); }, rollBack() { db.exec('ROLLBACK'); }
  };
} };
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@kit.')) return {url:'yiyu-test:'+specifier,shortCircuit:true};
    if (specifier.startsWith('.') && context.parentURL?.includes('/model/')) return nextResolve(specifier.endsWith('.ts') ? specifier : specifier+'.ts',context);
    if (specifier.startsWith('../model/')) return nextResolve(specifier+'.ts',context);
    return nextResolve(specifier,context);
  },
  load(url, context, nextLoad) {
    if (url === 'yiyu-test:@kit.ArkData') return {format:'module',source:'export const relationalStore = { SecurityLevel:{S1:1}, getRdbStore:globalThis.__yiyuRdbTest.getRdbStore }; export const preferences={async deletePreferences(){}};',shortCircuit:true};
    if (url === 'yiyu-test:@kit.CoreFileKit') return {format:'module',source:'import fs from "node:fs"; export const fileIo={accessSync:fs.existsSync,unlinkSync:fs.unlinkSync};',shortCircuit:true};
    if (url.endsWith('/services/LedgerStore.ets')) {
      let source = readFileSync(fileURLToPath(url),'utf8');
      source = source.replace(/import \{([^}]+)\} from ('[^']+');/g, (all, names, from) => {
        const values = names.split(',').map(s=>s.trim()).filter(name=>!['common','CustomCategory','LedgerEntry','MonthlyBudget','RecognitionResult'].includes(name));
        return values.length ? `import { ${values.join(', ')} } from ${from};` : '';
      });
      return {format:'module',source:stripTypeScriptTypes(source,{mode:'strip'}),shortCircuit:true};
    }
    return nextLoad(url,context);
  }
});
const { LedgerStore } = await import('../harmony/entry/src/main/ets/services/LedgerStore.ets');
const directories = [];
after(()=>{ for (const db of handles) db.close(); for (const dir of directories) {
  assert.ok(path.resolve(dir).startsWith(path.join(tmpdir(),'yiyu-store-'))); rmSync(dir,{recursive:true,force:true});
} });
async function book() {
  const directory = mkdtempSync(path.join(tmpdir(),'yiyu-store-')); directories.push(directory);
  const store = new LedgerStore(); await store.open({filesDir:directory});
  return {store,directory,db:databases.get(path.join(directory,'YiyuLedger.db'))};
}
const entry = (overrides={}) => ({id:'item_1',kind:'expense',amountMinor:900,currency:'CNY',categoryId:'food',title:'米线',occurredOn:'2026-10-11',timeHint:'中午',note:'',source:'voice',groupId:'',linkedEntryId:'',createdAt:1000,updatedAt:1000,deletedAt:0,...overrides});
const proposal = (title,amount) => ({kind:'expense',title,amount_minor:amount,category_id:'food',occurred_on:'2026-10-11',time_hint:'',note:'',issues:[]});

test('upgrade removes legacy recycle-bin rows without modifying the remaining bill', async()=>{
  const {store,directory,db} = await book(); const kept = entry();
  await store.save(kept);
  db.prepare('INSERT INTO entries VALUES (?,?)').run('old',JSON.stringify(entry({id:'old',title:'已删除内容',deletedAt:1200})));
  await store.open({filesDir:directory});
  assert.deepEqual(await store.all(),[kept]);
});

test('deletion removes SQLite rows and clears refund links without deleting independent bills', async()=>{
  const {store,db} = await book();
  await store.save(entry()); await store.save(entry({id:'refund',kind:'refund',linkedEntryId:'item_1',amountMinor:300}));
  await store.remove('item_1');
  const remaining = await store.all();
  assert.equal(remaining.length,1); assert.equal(remaining[0].id,'refund');
  assert.equal(remaining[0].linkedEntryId,''); assert.equal(remaining[0].amountMinor,300);
  assert.equal(db.prepare('SELECT count(*) AS count FROM entries WHERE id=?').get('item_1').count,0);
});

test('deleting a saved voice bill compacts related cache and audio, keeping unconfirmed bills', async()=>{
  const {store,directory} = await book();
  const audioPath = directory.replaceAll('\\','/')+'/audio_1000_123.wav'; writeFileSync(audioPath,'test audio');
  await store.save(entry({id:'audio_1000_123_item_0',groupId:'audio_1000_123'}));
  await store.putDraft({id:'audio_1000_123',path:audioPath,referenceDate:'2026-10-11',createdAt:1000,savedCount:1,
    resultJson:JSON.stringify({transcript:'米线9元，晚餐15元',drafts:[proposal('米线',900),proposal('晚餐',1500)],issues:[]})});
  // Native context uses forward-slash paths; normalize the test fixture in the same way.
  store.audioDirectory = directory.replaceAll('\\','/');
  await store.remove('audio_1000_123_item_0');
  assert.equal((await store.all()).length,0); assert.equal(existsSync(audioPath),false);
  const [draft] = await store.drafts(); const result = JSON.parse(draft.resultJson);
  assert.deepEqual(result.drafts,[proposal('晚餐',1500)]); assert.equal(result.transcript,'');
  assert.equal(draft.resultOffset,1); assert.equal(draft.savedCount,1); assert.equal(draft.path,'');
  assert.equal(draft.resultJson.includes('米线'),false);
  await store.saveConfirmedDraft(entry({id:'audio_1000_123_item_1',title:'晚餐',groupId:'audio_1000_123',amountMinor:1500}),draft);
  assert.equal(draft.savedCount,2); assert.equal(JSON.parse(draft.resultJson).drafts.length,0);
});

test('custom-category imports are transactional and used labels cannot be deleted',async()=>{
  const {store,db} = await book(); const cat = {id:'custom_pet',kind:'expense',name:'宠物'};
  await store.addCategory(cat); await store.save(entry({categoryId:cat.id,title:'猫粮'}));
  await assert.rejects(store.removeUnusedCategory(cat.id));
  await store.remove('item_1'); await store.removeUnusedCategory(cat.id); assert.deepEqual(store.categories(),[]);
  db.exec("CREATE TRIGGER reject_import BEFORE INSERT ON entries WHEN NEW.id='reject' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
  await assert.rejects(store.merge([entry({id:'reject',categoryId:cat.id})],[cat]));
  assert.deepEqual(store.categories(),[]);
  assert.equal(db.prepare('SELECT count(*) AS count FROM categories').get().count,0);
  assert.equal((await store.all()).length,0);
});

test('budget settings survive reopen and ledger deletion does not remove the selected limit',async()=>{
  const {store,directory} = await book();
  await store.setMonthlyBudget({month:'2026-10',amountMinor:10000}); await store.save(entry()); await store.remove('item_1');
  await store.open({filesDir:directory});
  assert.deepEqual(store.monthlyBudgets(),[{month:'2026-10',amountMinor:10000}]);
  await assert.rejects(store.setMonthlyBudget({month:'2026-10',amountMinor:-1}));
  assert.equal(store.monthlyBudgets()[0].amountMinor,10000);
});
test('budget and bill imports commit together and existing monthly choices are never overwritten',async()=>{
  const {store,db}=await book(); await store.setMonthlyBudget({month:'2026-10',amountMinor:10000});
  db.exec("CREATE TRIGGER reject_budget_import BEFORE INSERT ON entries WHEN NEW.id='reject' BEGIN SELECT RAISE(ABORT,'test failure'); END");
  await assert.rejects(store.merge([entry({id:'reject'})],[],[{month:'2026-11',amountMinor:12000}]));
  assert.deepEqual(store.monthlyBudgets(),[{month:'2026-10',amountMinor:10000}]);
  assert.equal(db.prepare('SELECT count(*) AS count FROM monthly_budgets').get().count,1);
  await store.merge([entry()],[],[{month:'2026-10',amountMinor:999},{month:'2026-11',amountMinor:12000}]);
  assert.deepEqual(store.monthlyBudgets(),[{month:'2026-10',amountMinor:10000},{month:'2026-11',amountMinor:12000}]);
});
