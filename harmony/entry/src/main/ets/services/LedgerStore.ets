import { common } from '@kit.AbilityKit';
import { relationalStore, preferences } from '@kit.ArkData';
import { CustomCategory, LedgerEntry, MonthlyBudget, validateEntry, validateCategoryCatalog, mergeCategoryCatalog, validateMonthlyBudgets, mergeMonthlyBudgets } from '../model/Ledger';
import { RecognitionResult } from '../model/CloudParsing';
import { compactRecognition, pendingRecognition } from '../model/RecordingPolicy';
import { fileIo } from '@kit.CoreFileKit';

export interface AudioDraft {
  id: string;
  path: string;
  referenceDate: string;
  createdAt: number;
  resultJson: string;
  savedCount: number;
  resultOffset?: number;
}

export class LedgerStore {
  private db: relationalStore.RdbStore | undefined = undefined;
  private custom: CustomCategory[] = [];
  private monthly: MonthlyBudget[] = [];
  private audioDirectory: string = '';

  async open(context: common.UIAbilityContext): Promise<void> {
    const config: relationalStore.StoreConfig = {
      name: 'YiyuLedger.db', securityLevel: relationalStore.SecurityLevel.S1
    };
    this.db = await relationalStore.getRdbStore(context, config);
    this.audioDirectory = context.filesDir;
    await this.db.executeSql('PRAGMA secure_delete = ON');
    await this.db.executeSql('CREATE TABLE IF NOT EXISTS entries (id TEXT PRIMARY KEY, value TEXT NOT NULL)');
    await this.db.executeSql('CREATE TABLE IF NOT EXISTS audio_drafts (id TEXT PRIMARY KEY, value TEXT NOT NULL)');
    await this.db.executeSql('CREATE TABLE IF NOT EXISTS categories (id TEXT PRIMARY KEY, value TEXT NOT NULL)');
    await this.db.executeSql('CREATE TABLE IF NOT EXISTS monthly_budgets (id TEXT PRIMARY KEY, value TEXT NOT NULL)');
    const result: relationalStore.ResultSet = await this.db.querySql('SELECT value FROM categories ORDER BY id');
    const custom: CustomCategory[] = [];
    try { while (result.goToNextRow()) custom.push(JSON.parse(result.getString(0)) as CustomCategory); }
    finally { result.close(); }
    const error: string = validateCategoryCatalog(custom);
    if (error) throw new Error('分类内容损坏，请使用备份恢复');
    this.custom = custom;
    const budgetResult: relationalStore.ResultSet = await this.db.querySql('SELECT value FROM monthly_budgets ORDER BY id');
    const budgets: MonthlyBudget[] = [];
    try { while (budgetResult.goToNextRow()) budgets.push(JSON.parse(budgetResult.getString(0)) as MonthlyBudget); }
    finally { budgetResult.close(); }
    const budgetError: string = validateMonthlyBudgets(budgets);
    if (budgetError) throw new Error(budgetError);
    this.monthly = budgets;
    // Migrate the old recycle bin once and compact any partially confirmed legacy recording.
    const obsolete: string[] = (await this.all()).filter((entry: LedgerEntry) => entry.deletedAt > 0).map((entry: LedgerEntry) => entry.id);
    await this.removeEntries(obsolete);
    // Remove legacy development connection settings; the encrypted personal key is a separate asset.
    try { await preferences.deletePreferences(context, 'yiyu_connection'); } catch (error) {}
  }

  categories(): CustomCategory[] { return this.custom.slice(); }
  monthlyBudgets(): MonthlyBudget[] { return this.monthly.slice(); }

  async setMonthlyBudget(budget: MonthlyBudget): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    const next: MonthlyBudget[] = this.monthly.filter((item: MonthlyBudget) => item.month !== budget.month).concat([budget]);
    const error: string = validateMonthlyBudgets(next);
    if (error) throw new Error(error);
    await this.db.executeSql('INSERT OR REPLACE INTO monthly_budgets (id, value) VALUES (?, ?)', [budget.month, JSON.stringify(budget)]);
    this.monthly = next;
  }

  async addCategory(category: CustomCategory): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    const next: CustomCategory[] = this.custom.concat([category]);
    const error: string = validateCategoryCatalog(next);
    if (error) throw new Error(error);
    await this.db.executeSql('INSERT INTO categories (id, value) VALUES (?, ?)', [category.id, JSON.stringify(category)]);
    this.custom = next;
  }

  async removeUnusedCategory(id: string): Promise<void> {
    if (!this.db || !this.custom.some((item: CustomCategory) => item.id === id)) throw new Error('分类不存在');
    if ((await this.all()).some((entry: LedgerEntry) => entry.categoryId === id)) throw new Error('该分类已有账单，暂不能移除');
    for (const draft of await this.drafts()) {
      if (!draft.resultJson) continue;
      const result: RecognitionResult = pendingRecognition(draft);
      if (result.drafts.some((item) => item.category_id === id)) throw new Error('该分类仍有待确认账单，请先处理');
    }
    await this.db.executeSql('DELETE FROM categories WHERE id = ?', [id]);
    this.custom = this.custom.filter((item: CustomCategory) => item.id !== id);
  }

  async all(): Promise<LedgerEntry[]> {
    if (!this.db) throw new Error('账本尚未打开');
    const result: relationalStore.ResultSet = await this.db.querySql('SELECT value FROM entries');
    const entries: LedgerEntry[] = [];
    try {
      while (result.goToNextRow()) {
        const entry: LedgerEntry = JSON.parse(result.getString(0)) as LedgerEntry;
        if (validateEntry(entry, this.custom) !== '') throw new Error('账本内容损坏，请使用备份恢复');
        entries.push(entry);
      }
    } finally {
      result.close();
    }
    return entries;
  }

  async save(entry: LedgerEntry): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    const message: string = validateEntry(entry, this.custom);
    if (message) throw new Error(message);
    if (entry.deletedAt !== 0) throw new Error('删除账单请使用统一删除操作');
    await this.db.executeSql('INSERT OR REPLACE INTO entries (id, value) VALUES (?, ?)', [entry.id, JSON.stringify(entry)]);
  }

  async merge(entries: LedgerEntry[], custom: CustomCategory[] = [], budgets: MonthlyBudget[] = []): Promise<number> {
    if (!this.db) throw new Error('账本尚未打开');
    const combined: CustomCategory[] = mergeCategoryCatalog(this.custom, custom);
    const combinedBudgets: MonthlyBudget[] = mergeMonthlyBudgets(this.monthly, budgets);
    const active: LedgerEntry[] = entries.filter((entry: LedgerEntry) => entry.deletedAt === 0);
    for (const entry of active) {
      const error: string = validateEntry(entry, combined);
      if (error) throw new Error(error);
    }
    const existing: string[] = (await this.all()).map((entry: LedgerEntry) => entry.id);
    let inserted: number = 0;
    this.db.beginTransaction();
    try {
      for (const budget of budgets) {
        if (!this.monthly.some((item: MonthlyBudget) => item.month === budget.month))
          await this.db.executeSql('INSERT INTO monthly_budgets (id, value) VALUES (?, ?)', [budget.month, JSON.stringify(budget)]);
      }
      for (const category of custom) {
        if (!this.custom.some((item: CustomCategory) => item.id === category.id))
          await this.db.executeSql('INSERT INTO categories (id, value) VALUES (?, ?)', [category.id, JSON.stringify(category)]);
      }
      for (const entry of active) {
        if (existing.includes(entry.id)) continue;
        await this.db.executeSql('INSERT INTO entries (id, value) VALUES (?, ?)', [entry.id, JSON.stringify(entry)]);
        inserted++;
      }
      this.db.commit();
      this.custom = combined;
      this.monthly = combinedBudgets;
    } catch (error) {
      this.db.rollBack();
      throw new Error('导入未完成，原账本已保留');
    }
    return inserted;
  }

  async saveConfirmedDraft(entry: LedgerEntry, draft: AudioDraft): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    const next: AudioDraft = {
      id: draft.id, path: draft.path, referenceDate: draft.referenceDate, createdAt: draft.createdAt,
      resultJson: draft.resultJson, savedCount: draft.savedCount + 1, resultOffset: draft.resultOffset || 0
    };
    next.resultJson = compactRecognition(next); next.resultOffset = next.savedCount;
    this.db.beginTransaction();
    try {
      await this.save(entry);
      await this.putDraft(next);
      this.db.commit();
      draft.savedCount = next.savedCount;
      draft.resultJson = next.resultJson; draft.resultOffset = next.resultOffset;
    } catch (error) {
      this.db.rollBack();
      throw new Error('保存未完成，请重试');
    }
  }

  async remove(id: string): Promise<void> { await this.removeEntries([id]); }

  private clearAudio(path: string): void {
    if (!path) return;
    if (!path.startsWith(this.audioDirectory + '/') || !/^audio_\d+_\d+\.wav$/.test(path.slice(this.audioDirectory.length + 1))) throw new Error('录音位置异常，请保留并检查');
    if (fileIo.accessSync(path)) fileIo.unlinkSync(path);
  }

  private async removeEntries(ids: string[]): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    const entries: LedgerEntry[] = await this.all();
    const groups: string[] = entries.filter((entry: LedgerEntry) => ids.includes(entry.id) && entry.groupId !== '')
      .map((entry: LedgerEntry) => entry.groupId);
    const drafts: AudioDraft[] = await this.drafts();
    this.db.beginTransaction();
    try {
      for (const id of ids) await this.db.executeSql('DELETE FROM entries WHERE id = ?', [id]);
      for (const entry of entries) {
        if (!ids.includes(entry.id) && ids.includes(entry.linkedEntryId)) {
          entry.linkedEntryId = ''; await this.save(entry);
        }
      }
      for (const draft of drafts) {
        if (!draft.resultJson || draft.savedCount === 0 && !groups.includes(draft.id)) continue;
        // Keep independent pending bills; discard only already-confirmed proposal copies and the original text/audio.
        try { draft.resultJson = compactRecognition(draft); }
        catch (error) {
          if (groups.includes(draft.id)) throw new Error('相关未完成记录异常，请先处理');
          continue;
        }
        draft.resultOffset = draft.savedCount;
        this.clearAudio(draft.path); draft.path = '';
        if (pendingRecognition(draft).drafts.length === 0) await this.removeDraft(draft.id);
        else await this.putDraft(draft);
      }
      this.db.commit();
    } catch (error) {
      this.db.rollBack(); throw new Error('删除或旧数据整理未完成，账本已保留，请重试');
    }
    if (ids.length > 0) {
      try { const checkpoint: relationalStore.ResultSet = await this.db.querySql('PRAGMA wal_checkpoint(TRUNCATE)'); checkpoint.close(); }
      catch (error) { /* Logical deletion is committed; SQLite may finish checkpointing on the next close. */ }
    }
  }

  async putDraft(draft: AudioDraft): Promise<void> {
    if (!this.db) throw new Error('账本尚未打开');
    await this.db.executeSql('INSERT OR REPLACE INTO audio_drafts (id, value) VALUES (?, ?)', [draft.id, JSON.stringify(draft)]);
  }

  async drafts(): Promise<AudioDraft[]> {
    if (!this.db) return [];
    const result: relationalStore.ResultSet = await this.db.querySql('SELECT value FROM audio_drafts');
    const drafts: AudioDraft[] = [];
    try {
      while (result.goToNextRow()) drafts.push(JSON.parse(result.getString(0)) as AudioDraft);
    } finally {
      result.close();
    }
    return drafts.sort((a: AudioDraft, b: AudioDraft) => b.createdAt - a.createdAt);
  }

  async removeDraft(id: string): Promise<void> {
    if (this.db) await this.db.executeSql('DELETE FROM audio_drafts WHERE id = ?', [id]);
  }
}

export const ledgerStore: LedgerStore = new LedgerStore();
