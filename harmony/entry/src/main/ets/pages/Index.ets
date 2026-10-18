import { abilityAccessCtrl, common, PermissionRequestResult } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { promptAction, window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { BackupDocument, Category, CustomCategory, CategoryTotal, LedgerEntry, MonthlyBudget, Period, Summary, KINDS, categoryById, categoryScope,
  categoriesForKind, dateKey, decimalAmount, filterEntries, formatMoney, kindName,
  parseMinor, periodFor, summarize, categoryTotals, trendPoints, validateEntry, inPeriod } from '../model/Ledger';
import { AudioDraft, ledgerStore } from '../services/LedgerStore';
import { AudioRecorder } from '../services/AudioRecorder';
import { DirectCloudClient } from '../services/DirectCloudClient';
import { RecognitionResult, RecognizedDraft, RecognitionSession } from '../model/CloudParsing';
import { recordingDisposition } from '../model/RecordingPolicy';
import { suggestedCategories } from '../model/CategorySuggestions';
import { LedgerLayout, ledgerLayout } from '../model/ResponsiveLayout';
import { cloudKeyStore } from '../services/CloudKeyStore';
import { BackupService } from '../services/BackupService';
import { TrendChart } from '../components/TrendChart';
import { BudgetCard } from '../components/BudgetCard';
import { BudgetProgress, budgetMonthFor, monthlyBudgetProgress } from '../model/Budget';
import { iconResource } from '../components/Icons';
import { PALETTE } from '../model/Palette';
import { LedgerBrowseOptions, LedgerBrowseResult, LedgerDateScope, LedgerDay, browseLedger, ledgerDayLabel, ledgerDayPage } from '../model/LedgerBrowse';

@Entry
@Component
struct Index {
  @State entries: LedgerEntry[] = [];
  @State customCategories: CustomCategory[] = [];
  @State monthlyBudgets: MonthlyBudget[] = [];
  @State budgetInput: string = '';
  @State budgetEditingMonth: string = '';
  @State budgetError: string = '';
  @State openedSwipeId: string = '';
  @State windowWidth: number = 360;
  @State fontScale: number = 1;
  @State categoriesExpanded: boolean = false;
  @State creatingCategory: boolean = false;
  @State customCategoryName: string = '';
  @State customCategoryError: string = '';
  @State transcriptExpanded: boolean = false;
  @State drafts: AudioDraft[] = [];
  @State ready: boolean = false;
  @State tab: string = 'home';
  @State activeSheet: string = '';
  @State mode: string = 'month';
  @State periodOffset: number = 0;
  @State search: string = '';
  @State filterKind: string = 'all';
  @State categoryFilter: string = '';
  @State dateFilter: string = '';
  @State drillStart: string = '';
  @State drillEnd: string = '';
  @State browseToday: string = dateKey();
  @State ledgerScope: LedgerDateScope = { mode: 'recent', start: '', end: '', label: '最近三天' };
  @State ledgerDays: LedgerDay[] = [];
  @State ledgerMatchCount: number = 0;
  @State ledgerLoadedCount: number = 0;
  @State ledgerPickerDate: string = dateKey();
  @State rankingKind: string = 'expense';
  @State editorKind: string = 'expense';
  @State editorAmount: string = '';
  @State editorTitle: string = '';
  @State editorCategory: string = 'food';
  @State editorDate: string = dateKey();
  @State editorNote: string = '';
  @State editorIssues: string[] = [];
  @State transcript: string = '';
  @State proposalText: string = '';
  @State proposalNumber: number = 0;
  @State proposalTotal: number = 0;
  @State textInput: string = '';
  @State busy: boolean = false;
  @State recording: boolean = false;
  @State starting: boolean = false;
  @State fingerDown: boolean = false;
  @State cancelVoice: boolean = false;
  @State voiceLevel: number = 0;
  @State seconds: number = 0;
  @State keyState: string = '';
  @State cloudKeyInput: string = '';
  @State cloudKeyConfigured: boolean = false;
  @State queuedCount: number = 0;
  private editing: LedgerEntry | undefined = undefined;
  private pending: RecognizedDraft[] = [];
  private pendingSource: string = 'manual';
  private pendingGroup: string = '';
  private pendingTimeHint: string = '';
  private activeDraft: AudioDraft | undefined = undefined;
  private recorder: AudioRecorder = new AudioRecorder();
  private live: RecognitionSession | undefined = undefined;
  private voiceTouchId: number = -1;
  private startY: number = 0;
  private voiceTimer: number = -1;
  private initialized: boolean = false;
  private editorScroll: Scroller = new Scroller();
  private mainScroller: Scroller = new Scroller();
  private ledgerScroller: ListScroller = new ListScroller();
  private searchController: TextInputController = new TextInputController();
  private ledgerResult: LedgerBrowseResult | undefined = undefined;
  private ledgerLimit: number = 80;
  private dayChangeTimer: number = -1;
  private mainWindow: window.Window | undefined = undefined;
  private windowSizeHandler: (size: window.Size) => void = (size: window.Size) => {
    this.windowWidth = this.getUIContext().px2vp(size.width);
  };
  private customNameController: TextInputController = new TextInputController();
  private fontScaleHandler: (scale: number) => void = (scale: number) => { this.fontScale = scale; this.syncWindowWidth(); };
  private pageVisible: boolean = true;
  private backgroundHandler: () => void = () => { this.pageVisible = false; this.scheduleDayChange(); this.pauseRecording(); };
  private foregroundHandler: () => void = () => { this.pageVisible = true; this.scheduleDayChange(); this.syncWindowWidth(); if (this.initialized) this.refresh(); };

  aboutToAppear(): void {
    this.fontScale = this.context().config.fontSizeScale || 1;
    this.context().eventHub.on('yiyuFontScale', this.fontScaleHandler);
    this.context().eventHub.on('yiyuBackground', this.backgroundHandler);
    this.context().eventHub.on('yiyuForeground', this.foregroundHandler);
    window.getLastWindow(this.context()).then((main: window.Window) => {
      this.mainWindow = main; this.syncWindowWidth(); main.on('windowSizeChange', this.windowSizeHandler);
    }).catch(() => {});
    this.initialize();
    this.scheduleDayChange();
  }
  aboutToDisappear(): void {
    if (this.mainWindow) this.mainWindow.off('windowSizeChange', this.windowSizeHandler);
    this.context().eventHub.off('yiyuFontScale', this.fontScaleHandler);
    this.context().eventHub.off('yiyuBackground', this.backgroundHandler);
    this.context().eventHub.off('yiyuForeground', this.foregroundHandler);
    this.pageVisible = false; this.scheduleDayChange(); this.pauseRecording();
  }
  onPageHide(): void { this.pageVisible = false; this.scheduleDayChange(); this.pauseRecording(); }
  onPageShow(): void { this.pageVisible = true; this.scheduleDayChange(); if (this.initialized) this.refresh(); }
  onBackPress(): boolean {
    if (this.recording || this.starting) { this.fingerDown = false; this.cancelVoice = true; this.finishVoice(true); return true; }
    if (this.busy) { this.toast('正在处理，请稍候'); return true; }
    if (this.activeSheet === 'edit' && this.creatingCategory) { this.creatingCategory = false; return true; }
    if (this.activeSheet === 'edit' && this.categoriesExpanded) { this.categoriesExpanded = false; return true; }
    if (this.activeSheet) { this.dismissOverlay(); return true; }
    if (this.tab !== 'home') { this.changeTab('home'); return true; }
    return false;
  }

  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private toast(message: string): void { promptAction.showToast({ message: message, duration: 2500 }); }
  private showError(error: Object, fallback: string): void {
    const message: string = error instanceof Error ? error.message : fallback;
    this.toast(message || fallback);
  }
  private async recognizer(): Promise<DirectCloudClient> {
    return new DirectCloudClient(await cloudKeyStore.getKey(), this.customCategories);
  }
  private layout(): LedgerLayout { return ledgerLayout(this.windowWidth, this.fontScale); }
  private syncWindowWidth(): void {
    if (this.mainWindow) this.windowWidth = this.getUIContext().px2vp(this.mainWindow.getWindowProperties().windowRect.width);
  }
  private closeSwipeActions(): void {
    if (this.tab === 'ledger') { try { this.ledgerScroller.closeAllSwipeActions(); } catch (error) {} }
    this.openedSwipeId = '';
  }
  private changeTab(tab: string): void {
    this.closeSwipeActions(); this.tab = tab;
    if (tab === 'ledger') { this.rebuildLedgerView(); this.scrollLedgerTop(); }
    else this.mainScroller.scrollTo({ xOffset: 0, yOffset: 0 });
  }
  private scrollLedgerTop(): void { try { this.ledgerScroller.scrollToIndex(0, false); } catch (error) {} }
  private scheduleDayChange(): void {
    if (this.dayChangeTimer >= 0) { clearTimeout(this.dayChangeTimer); this.dayChangeTimer = -1; }
    if (!this.pageVisible) return;
    const now: Date = new Date();
    const midnight: number = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime();
    this.dayChangeTimer = setTimeout(() => {
      this.dayChangeTimer = -1;
      if (this.pageVisible) { this.closeSwipeActions(); this.rebuildLedgerView(); this.scheduleDayChange(); }
    }, Math.max(1000, midnight - Date.now() + 100));
  }
  private rebuildLedgerView(resetLimit: boolean = true): void {
    const today: string = dateKey();
    if (resetLimit || this.browseToday !== today) this.ledgerLimit = 80;
    this.browseToday = today;
    const options: LedgerBrowseOptions = { today: today, search: this.search, kind: this.filterKind,
      categoryId: this.categoryFilter, dateFilter: this.dateFilter, rangeStart: this.drillStart, rangeEnd: this.drillEnd };
    this.ledgerResult = browseLedger(this.entries, options, this.customCategories);
    this.ledgerScope = this.ledgerResult.scope;
    this.updateLedgerPage();
  }
  private updateLedgerPage(): void {
    if (!this.ledgerResult) return;
    this.ledgerDays = ledgerDayPage(this.ledgerResult, this.ledgerLimit);
    this.ledgerMatchCount = this.ledgerResult.entries.length;
    this.ledgerLoadedCount = Math.min(this.ledgerLimit, this.ledgerMatchCount);
  }
  private loadMoreLedger(): void {
    if (this.ledgerLoadedCount >= this.ledgerMatchCount) return;
    this.ledgerLimit += 80; this.updateLedgerPage();
  }
  private updateLedgerSearch(value: string): void {
    this.closeSwipeActions(); this.search = value; this.rebuildLedgerView(); this.scrollLedgerTop();
  }
  private submitLedgerSearch(): void {
    this.searchController.stopEditing(); this.closeSwipeActions(); this.rebuildLedgerView(); this.scrollLedgerTop();
  }
  private resetLedgerScope(): void {
    this.searchController.stopEditing(); this.closeSwipeActions();
    this.search = ''; this.dateFilter = ''; this.drillStart = ''; this.drillEnd = ''; this.categoryFilter = '';
    this.rebuildLedgerView(); this.scrollLedgerTop();
  }
  private chooseLedgerDate(): void {
    this.searchController.stopEditing(); this.closeSwipeActions();
    this.ledgerPickerDate = this.dateFilter.length === 10 ? this.dateFilter :
      this.dateFilter.length === 7 ? this.dateFilter + '-01' : this.drillStart || dateKey();
    this.activeSheet = 'ledger-date';
  }
  private ledgerPickerWeekday(): string {
    const weekdays: string[] = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    return weekdays[new Date(this.ledgerPickerDate + 'T12:00:00').getDay()];
  }
  private ledgerPickerHeight(): number {
    const height: number = this.mainWindow ? this.getUIContext().px2vp(this.mainWindow.getWindowProperties().windowRect.height) : 760;
    return Math.max(140, Math.min(280, height * 0.85 - this.layout().controlHeight * 2 - 64));
  }
  private acceptLedgerDate(): void {
    this.dateFilter = this.ledgerPickerDate;
    this.drillStart = ''; this.drillEnd = ''; this.categoryFilter = ''; this.activeSheet = '';
    this.rebuildLedgerView(); this.scrollLedgerTop();
  }
  private category(id: string): Category { return categoryById(id, this.customCategories); }
  private editorCategories(): Category[] { return categoriesForKind(this.editorKind, this.customCategories); }
  private quickCategories(): Category[] {
    return suggestedCategories(this.editorKind, this.editorCategory, (this.proposalText || this.transcript) + ' ' + this.editorNote,
      this.editorTitle, this.entries, this.customCategories, this.layout().quickCount);
  }
  private resetEditorLayout(): void {
    this.categoriesExpanded = false; this.creatingCategory = false; this.customCategoryName = '';
    this.customCategoryError = ''; this.transcriptExpanded = false; this.proposalText = '';
    this.editorScroll.scrollTo({ xOffset: 0, yOffset: 0 });
  }
  private selectCategory(id: string): void {
    this.editorCategory = id; this.categoriesExpanded = false; this.creatingCategory = false;
  }
  private async createCategory(): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.customCategoryError = '';
    const category: CustomCategory = { id: 'custom_' + util.generateRandomUUID(false),
      name: this.customCategoryName.trim(), kind: categoryScope(this.editorKind) };
    try {
      await ledgerStore.addCategory(category); this.customCategories = ledgerStore.categories();
      this.customNameController.stopEditing(); this.selectCategory(category.id); this.customCategoryName = ''; this.toast('已创建并选用分类');
    } catch (error) {
      this.customCategoryError = error instanceof Error ? error.message : '分类创建未完成，请重试';
      this.toast(this.customCategoryError);
    } finally { this.busy = false; }
  }
  private amountFont(): number {
    return Math.max(16, Math.min(40 * Math.max(1, this.fontScale), (this.layout().sheetWidth - this.layout().padding * 2 - 35) /
      (Math.max(5, this.editorAmount.length) * 0.65)));
  }
  private offerRemoveCategory(category: Category): void {
    if (!category.id.startsWith('custom_') || this.busy) return;
    AlertDialog.show({ title: '移除“' + category.name + '”分类？', message: '仅能移除未被账单或待确认记录使用的自定义分类。',
      primaryButton: { value: '保留', action: () => {} },
      secondaryButton: { value: '移除', action: () => this.removeCategory(category.id) } });
  }
  private async removeCategory(id: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await ledgerStore.removeUnusedCategory(id); this.customCategories = ledgerStore.categories();
      if (this.editorCategory === id) this.editorCategory = this.editorCategories()[0].id;
      this.toast('已移除分类');
    } catch (error) { this.showError(error as Object, '分类移除未完成'); }
    finally { this.busy = false; }
  }
  private period(): Period { return periodFor(this.mode, dateKey(), this.periodOffset); }
  private monthSummary(): Summary { return summarize(this.entries, periodFor('month', dateKey())); }
  private total(): Summary { return summarize(this.entries, this.period()); }
  private budgetMonth(): string { return budgetMonthFor(this.mode, this.period(), dateKey()); }
  private budgetProgress(): BudgetProgress { return monthlyBudgetProgress(this.entries, this.monthlyBudgets, this.budgetMonth()); }
  private setupBudget(): void {
    this.budgetEditingMonth = this.budgetMonth();
    const limit: number = this.budgetProgress().limit;
    this.budgetInput = limit > 0 ? decimalAmount(limit) : ''; this.budgetError = ''; this.activeSheet = 'budget';
  }
  private async saveBudget(disable: boolean = false): Promise<void> {
    if (this.busy) return;
    const amount: number = disable ? 0 : parseMinor(this.budgetInput);
    if (amount < 0) { this.budgetError = '请输入大于0且最多两位小数的金额'; return; }
    this.busy = true;
    try {
      await ledgerStore.setMonthlyBudget({ month: this.budgetEditingMonth, amountMinor: amount });
      this.monthlyBudgets = ledgerStore.monthlyBudgets(); this.activeSheet = ''; this.budgetInput = ''; this.budgetError = '';
      this.toast(disable ? '已从该月起关闭预算，之后可重新设置' : '月预算已保存，后续月份自动沿用');
    } catch (error) { this.showError(error as Object, '预算保存失败'); }
    finally { this.busy = false; }
  }
  private todayEntries(): LedgerEntry[] { return filterEntries(this.entries, '').filter((entry: LedgerEntry) => entry.occurredOn === dateKey()); }
  private rankings(): CategoryTotal[] { return categoryTotals(this.entries, this.period(), this.rankingKind, this.customCategories); }
  private entryRevision(entry: LedgerEntry): string { return entry.id + '_' + entry.updatedAt; }

  private async initialize(): Promise<void> {
    try {
      await ledgerStore.open(this.context());
      try {
        this.cloudKeyConfigured = await cloudKeyStore.hasKey();
      } catch (error) { this.showError(error as Object, '密钥配置暂不可用'); }
      this.initialized = true;
      await this.refresh(); this.ready = true;
    } catch (error) { this.showError(error as Object, '账本打开失败'); }
  }
  private async refresh(): Promise<void> {
    this.customCategories = ledgerStore.categories();
    this.monthlyBudgets = ledgerStore.monthlyBudgets();
    this.entries = await ledgerStore.all();
    this.rebuildLedgerView(false);
    const drafts: AudioDraft[] = await ledgerStore.drafts();
    const retained: AudioDraft[] = [];
    for (const draft of drafts) {
      const action: string = recordingDisposition(draft, Date.now());
      try {
        if (action === 'discard') { await this.removeDraft(draft); continue; }
        if (action === 'release-audio') await this.releaseDraftAudio(draft);
      } catch (error) { /* Retry cleanup on the next refresh without blocking access to the ledger. */ }
      retained.push(draft);
    }
    this.drafts = retained;
  }

  private newEntry(): void {
    this.resetEditorLayout();
    this.activeDraft = undefined;
    this.editing = undefined; this.pending = []; this.queuedCount = 0; this.pendingSource = 'manual'; this.pendingGroup = '';
    this.editorKind = 'expense'; this.editorAmount = ''; this.editorTitle = ''; this.editorCategory = 'food';
    this.editorDate = dateKey(); this.editorNote = ''; this.editorIssues = []; this.transcript = ''; this.activeSheet = 'edit';
  }
  private edit(entry: LedgerEntry): void {
    this.resetEditorLayout();
    this.activeDraft = undefined;
    this.editing = entry; this.pending = []; this.queuedCount = 0; this.pendingSource = entry.source;
    this.editorKind = entry.kind; this.editorAmount = decimalAmount(entry.amountMinor); this.editorTitle = entry.title;
    this.editorCategory = entry.categoryId; this.editorDate = entry.occurredOn; this.editorNote = entry.note;
    this.editorIssues = []; this.transcript = ''; this.activeSheet = 'edit';
  }
  private setEditorKind(kind: string): void {
    this.editorKind = kind;
    if (!this.editorCategories().some((category: Category) => category.id === this.editorCategory)) this.editorCategory = this.editorCategories()[0].id;
    this.categoriesExpanded = false; this.creatingCategory = false; this.customCategoryError = '';
  }
  private async acceptResult(result: RecognitionResult, source: string, draft?: AudioDraft): Promise<void> {
    if (!result || !Array.isArray(result.drafts) || !Array.isArray(result.issues) || typeof result.transcript !== 'string') {
      throw new Error('未完成记录内容异常，请保留或移除后重新记账');
    }
    this.activeSheet = '';
    this.transcript = result.transcript;
    if (result.drafts.length === 0) {
      if (draft) await this.deleteDraft(draft);
      this.activeDraft = undefined; this.pending = []; this.queuedCount = 0; this.transcript = '';
      this.toast(result.issues.join('；') || '没有识别到记账内容'); return;
    }
    this.activeDraft = draft;
    if (draft && !draft.resultJson) {
      draft.resultJson = JSON.stringify(result); draft.savedCount = 0; await ledgerStore.putDraft(draft);
    }
    if (draft) {
      try { await this.releaseDraftAudio(draft); } catch (error) {}
      await this.refresh();
    }
    this.pending = result.drafts.slice(draft ? draft.savedCount - (draft.resultOffset || 0) : 0); this.pendingSource = source;
    this.proposalTotal = (draft ? draft.savedCount : 0) + this.pending.length;
    this.pendingGroup = draft ? draft.id : 'group_' + Date.now(); this.editing = undefined;
    if (this.pending.length === 0) { await this.discardActiveDraft(); this.toast('这段语音的账单已全部保存'); return; }
    this.nextProposal();
  }
  private nextProposal(): void {
    this.resetEditorLayout();
    const draft: RecognizedDraft | undefined = this.pending.shift();
    this.queuedCount = this.pending.length;
    this.proposalNumber = this.proposalTotal - this.pending.length;
    if (!draft) { this.activeSheet = ''; return; }
    this.editorKind = draft.kind; this.editorAmount = draft.amount_minor === null ? '' : decimalAmount(draft.amount_minor);
    this.editorTitle = draft.title; this.editorCategory = draft.category_id; this.editorDate = draft.occurred_on;
    this.editorNote = draft.note; this.editorIssues = draft.issues; this.activeSheet = 'edit';
    this.proposalText = draft.source_text || '';
    this.pendingTimeHint = draft.time_hint;
  }
  private async save(): Promise<void> {
    if (this.busy) return;
    const now: number = Date.now();
    const entry: LedgerEntry = {
      id: this.editing ? this.editing.id : this.activeDraft ? this.activeDraft.id + '_item_' + this.activeDraft.savedCount : 'entry_' + now + '_' + Math.floor(Math.random() * 1000000),
      kind: this.editorKind, amountMinor: parseMinor(this.editorAmount), currency: 'CNY',
      categoryId: this.editorCategory, title: this.editorTitle.trim(), occurredOn: this.editorDate,
      timeHint: this.editing ? this.editing.timeHint : this.pendingSource === 'manual' ? '' : this.pendingTimeHint, note: this.editorNote.trim(), source: this.pendingSource,
      groupId: this.editing ? this.editing.groupId : this.pendingGroup, linkedEntryId: this.editing ? this.editing.linkedEntryId : '',
      createdAt: this.editing ? this.editing.createdAt : now, updatedAt: now, deletedAt: 0
    };
    const message: string = validateEntry(entry, this.customCategories);
    if (message) { this.toast(message); return; }
    this.busy = true;
    let committed: boolean = false;
    try {
      if (this.activeDraft) await ledgerStore.saveConfirmedDraft(entry, this.activeDraft);
      else await ledgerStore.save(entry);
      committed = true;
      await this.refresh(); this.toast('已记下这一笔');
      if (this.pending.length > 0) this.nextProposal();
      else { this.activeSheet = ''; await this.discardActiveDraft(); }
    } catch (error) {
      if (committed) {
        this.activeSheet = ''; this.pending = []; this.activeDraft = undefined;
        this.toast('账单已保存，列表刷新失败，请重新打开账本查看');
      } else this.showError(error as Object, '保存失败，请重试');
    }
    finally { this.busy = false; }
  }
  private confirmRemoveEntry(entry?: LedgerEntry): void {
    const target: LedgerEntry | undefined = entry || this.editing;
    if (!target || this.busy) return;
    this.closeSwipeActions();
    AlertDialog.show({ title: '删除“' + target.title + '”？', message: '将从账本、统计、月预算进度和相关缓存中删除，应用内不再保留回收站副本。已导出的文件不受影响。',
      primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '删除', action: () => this.removeEntry(target.id) } });
  }
  private async removeEntry(id: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    let committed: boolean = false;
    try {
      await ledgerStore.remove(id); committed = true;
      this.editing = undefined; this.activeDraft = undefined; this.pending = []; this.queuedCount = 0;
      this.editorTitle = ''; this.editorAmount = ''; this.editorNote = ''; this.transcript = ''; this.activeSheet = '';
      await this.refresh(); this.toast('账单及相关缓存已删除');
    } catch (error) {
      if (committed) this.toast('账单已删除，请重新打开应用刷新显示');
      else this.showError(error as Object, '删除失败');
    }
    finally { this.busy = false; }
  }
  private dismissOverlay(): void {
    if (this.busy) return;
    if (this.activeSheet === 'edit' && !this.editing && this.pendingSource !== 'manual') {
      // Voice source remains retryable until every proposal is saved.
      this.toast(this.activeDraft ? '未确认账单已保留，可在首页继续' : '未保存的识别结果已关闭');
    }
    if (this.activeSheet === 'api-key') { this.cloudKeyInput = ''; this.keyState = ''; }
    this.pending = []; this.queuedCount = 0; this.activeDraft = undefined; this.activeSheet = '';
  }

  private touchVoice(event: TouchEvent): void {
    event.stopPropagation();
    if (event.type === TouchType.Cancel) {
      this.fingerDown = false; this.voiceTouchId = -1; this.cancelVoice = true; this.finishVoice(true); return;
    }
    if (event.changedTouches.length === 0) return;
    if (event.type === TouchType.Down) {
      if (!this.ready || this.busy || this.recording || this.starting || this.fingerDown) return;
      this.voiceTouchId = event.changedTouches[0].id;
      this.fingerDown = true; this.startY = event.changedTouches[0].screenY; this.cancelVoice = false; this.beginVoice(); return;
    }
    const touch: TouchObject | undefined = event.changedTouches.find((value: TouchObject) => value.id === this.voiceTouchId);
    if (!touch) return;
    if (event.type === TouchType.Move) this.cancelVoice = this.startY - touch.screenY > 70;
    if (event.type === TouchType.Up) {
      this.fingerDown = false; this.voiceTouchId = -1; this.finishVoice(this.cancelVoice);
    }
  }
  private async beginVoice(): Promise<void> {
    if (!this.ready || this.busy || this.recording || this.starting) return;
    if (!this.cloudKeyConfigured) { this.fingerDown = false; this.openKeySetup(); return; }
    this.starting = true;
    let captureRequested: boolean = false;
    try {
      const permission: PermissionRequestResult = await abilityAccessCtrl.createAtManager()
        .requestPermissionsFromUser(this.context(), ['ohos.permission.MICROPHONE']);
      if (permission.authResults[0] !== 0) { this.toast('需要麦克风权限才能语音记账'); return; }
      const client: DirectCloudClient = await this.recognizer();
      if (!this.fingerDown || !this.pageVisible) { if (this.pageVisible) this.toast('权限已准备好，请再次按住说话'); return; }
      this.transcript = ''; this.seconds = 0; this.voiceLevel = 0; this.activeSheet = 'voice';
      this.live = client.live(dateKey(), (text: string) => this.transcript = text);
      captureRequested = true;
      await this.recorder.start(this.context(), dateKey(), (frame: ArrayBuffer) => { if (this.live) this.live.push(frame); },
        (level: number) => this.voiceLevel = level, () => { this.fingerDown = false; this.finishVoice(false); });
      this.recording = true;
      if (!this.pageVisible) { await this.pauseRecording(); return; }
      this.voiceTimer = setInterval(() => { this.seconds++; if (this.seconds >= 30) { this.fingerDown = false; this.finishVoice(false); } }, 1000);
      if (!this.fingerDown) this.finishVoice(this.cancelVoice);
    } catch (error) {
      if (this.live) this.live.cancel();
      if (captureRequested) { try { await this.recorder.cancel(); } catch (cleanupError) {} }
      this.activeSheet = ''; this.showError(error as Object, '录音失败');
    }
    finally { this.starting = false; }
  }
  private async finishVoice(cancel: boolean): Promise<void> {
    if (!this.recording) return;
    this.busy = true; this.recording = false; clearInterval(this.voiceTimer); this.voiceLevel = 0;
    const live: RecognitionSession | undefined = this.live; this.live = undefined;
    if (cancel) {
      try {
        if (live) live.cancel();
        await this.recorder.cancel(); this.toast('已取消录音');
      } catch (error) { this.showError(error as Object, '录音清理失败，请稍后重试'); }
      finally {
        this.activeSheet = ''; this.activeDraft = undefined;
        try { await this.refresh(); } finally { this.busy = false; }
      }
      return;
    }
    try {
      const draft: AudioDraft | undefined = await this.recorder.stop();
      if (!draft) { if (live) live.cancel(); this.toast('录音太短，请按住后再说'); this.activeSheet = ''; return; }
      this.activeDraft = draft; await this.refresh();
      if (!live) throw new Error('识别连接未建立，录音可稍后重试');
      const result: RecognitionResult = await live.finish();
      await this.acceptResult(result, 'voice', draft);
    } catch (error) {
      if (live) live.cancel();
      this.activeSheet = ''; this.activeDraft = undefined;
      this.showError(error as Object, '识别未完成，可在首页重试'); await this.refresh();
    } finally { this.busy = false; }
  }
  private async pauseRecording(): Promise<void> {
    this.fingerDown = false;
    if (!this.recording) return;
    this.busy = true; this.recording = false; clearInterval(this.voiceTimer);
    if (this.live) this.live.cancel(); this.live = undefined;
    try { await this.recorder.stop(); await this.refresh(); } catch (error) {}
    finally { this.activeSheet = ''; this.busy = false; }
  }
  private async parseText(): Promise<void> {
    if (!this.cloudKeyConfigured) { this.openKeySetup(); return; }
    if (!this.textInput.trim()) { this.toast('先写下这一笔的描述'); return; }
    this.busy = true;
    try { await this.acceptResult(await (await this.recognizer()).parse(this.textInput, dateKey()), 'text'); }
    catch (error) { this.showError(error as Object, '解析失败，输入内容已保留'); }
    finally { this.busy = false; }
  }
  private async retryDraft(draft: AudioDraft): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const result: RecognitionResult = draft.resultJson ? JSON.parse(draft.resultJson) as RecognitionResult : await (await this.recognizer()).retry(draft);
      await this.acceptResult(result, 'voice', draft);
    }
    catch (error) { this.showError(error as Object, '重试失败，录音已保留'); }
    finally { this.busy = false; }
  }
  private async discardActiveDraft(): Promise<void> {
    if (!this.activeDraft) return;
    await this.deleteDraft(this.activeDraft); this.activeDraft = undefined;
  }
  private async releaseDraftAudio(draft: AudioDraft): Promise<void> {
    if (!draft.path) return;
    if (fileIo.accessSync(draft.path)) fileIo.unlinkSync(draft.path);
    draft.path = ''; await ledgerStore.putDraft(draft);
  }
  private async removeDraft(draft: AudioDraft): Promise<void> {
    if (draft.path && fileIo.accessSync(draft.path)) fileIo.unlinkSync(draft.path);
    await ledgerStore.removeDraft(draft.id);
  }
  private async deleteDraft(draft: AudioDraft): Promise<void> {
    try { await this.removeDraft(draft); await this.refresh(); }
    catch (error) { this.showError(error as Object, '未能删除，请稍后重试'); }
  }
  private openKeySetup(): void {
    this.cloudKeyInput = ''; this.keyState = ''; this.activeSheet = 'api-key';
  }
  private async saveCloudKey(): Promise<void> {
    if (this.busy) return;
    this.busy = true; this.keyState = '正在验证密钥…';
    try {
      const key: string = cloudKeyStore.validateKey(this.cloudKeyInput);
      // Verify before replacing a working key. Network errors cannot erase the existing credential.
      await new DirectCloudClient(key).checkConnection();
      await cloudKeyStore.saveKey(key); this.cloudKeyInput = ''; this.cloudKeyConfigured = true;
      this.keyState = ''; this.activeSheet = ''; this.toast('已启用语音记账');
    } catch (error) { this.keyState = error instanceof Error ? error.message : '未能启用，请检查网络和密钥'; }
    finally { this.busy = false; }
  }
  private async removeCloudKey(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try { await cloudKeyStore.removeKey(); this.cloudKeyConfigured = false; this.cloudKeyInput = ''; this.keyState = ''; this.toast('密钥已移除，账本已保留'); }
    catch (error) { this.showError(error as Object, '移除失败'); }
    finally { this.busy = false; }
  }
  private async exportData(csv: boolean): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try { if (await BackupService.save(this.context(), this.entries, csv, this.customCategories, this.monthlyBudgets)) this.toast('已导出'); }
    catch (error) { this.showError(error as Object, '导出未完成'); }
    finally { this.busy = false; }
  }
  private async importData(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const backup: BackupDocument | undefined = await BackupService.read(this.context());
      if (backup) { const count: number = await ledgerStore.merge(backup.entries, backup.categories, backup.monthlyBudgets); await this.refresh(); this.toast('导入了' + count + '笔，已有编号和月份设置已跳过'); }
    } catch (error) { this.showError(error as Object, '导入未完成'); }
    finally { this.busy = false; }
  }
  private drill(category: string, kind: string, date: string = ''): void {
    this.drillStart = this.tab === 'stats' ? this.period().start : '';
    this.drillEnd = this.tab === 'stats' ? this.period().end : '';
    this.categoryFilter = category; this.filterKind = kind; this.dateFilter = date; this.search = ''; this.changeTab('ledger');
  }

  private iconTint(color: string): ColorFilter {
    // Tint the #234236 stroke while preserving the SVG's transparent interior.
    return new ColorFilter([
      parseInt(color.slice(1, 3), 16) / 35, 0, 0, 0, 0,
      0, parseInt(color.slice(3, 5), 16) / 66, 0, 0, 0,
      0, 0, parseInt(color.slice(5, 7), 16) / 54, 0, 0,
      0, 0, 0, 1, 0
    ]);
  }
  @Builder
  icon(name: string, size: number = 20, color: string = PALETTE.forest) {
    Image(iconResource(name)).width(size).height(size).colorFilter(this.iconTint(color)).objectFit(ImageFit.Contain)
  }
  @Builder
  pageHeader(title: string, subtitle: string) {
    Row() {
      if (this.tab === 'home') { Image($r('app.media.app_icon')).width(34).height(34).margin({ right: 10 }) }
      Column({ space: 5 }) {
        Text(title).fontSize(24).fontWeight(FontWeight.Bold).fontColor(PALETTE.forest)
        Text(subtitle).fontSize(11).fontColor(PALETTE.muted)
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Blank()
      Button({ type: ButtonType.Circle }) { this.icon(this.tab === 'settings' ? 'close' : 'settings', 19) }
        .width(44).height(44).backgroundColor(PALETTE.field)
        .accessibilityText(this.tab === 'settings' ? '关闭设置' : '设置')
        .onClick(() => this.changeTab(this.tab === 'settings' ? 'home' : 'settings'))
    }.width('100%').padding({ top: 20, bottom: 20 })
  }
  @Builder
  sectionTitle(title: string, action: string = '') {
    Row() {
      Text(title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(PALETTE.forest)
      Blank()
      if (action) { Text(action + ' ›').fontSize(11).fontColor(PALETTE.muted).onClick(() => this.drill('', 'all')) }
    }.width('100%').margin({ top: 23, bottom: 14 })
  }
  @Builder
  empty(title: string, description: string) {
    Column({ space: 10 }) {
      this.icon('ledger', 26, PALETTE.muted)
      Text(title).fontSize(14).fontColor(PALETTE.muted)
      Text(description).fontSize(11).fontColor(PALETTE.muted).textAlign(TextAlign.Center).lineHeight(19 * Math.max(1, this.fontScale))
    }.width('100%').padding(30).backgroundColor(PALETTE.field).borderRadius(18)
  }
  @Builder
  entryRow(entry: LedgerEntry) {
    Row({ space: 12 }) {
      Row() { this.icon(this.category(entry.categoryId).icon, 19, this.category(entry.categoryId).color) }
        .justifyContent(FlexAlign.Center).width(40).height(40).borderRadius(13).backgroundColor(this.category(entry.categoryId).background)
      Column({ space: 6 }) {
        Text(entry.title).fontSize(14).fontColor(PALETTE.ink).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.category(entry.categoryId).name + ' · ' + (entry.timeHint || kindName(entry.kind))).fontSize(10).fontColor(PALETTE.muted).maxLines(2)
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text((entry.kind === 'expense' ? '−' : entry.kind === 'income' || entry.kind === 'refund' ? '+' : '') + formatMoney(entry.amountMinor))
        .fontSize(16).fontColor(entry.kind === 'income' ? PALETTE.income : entry.kind === 'refund' ? PALETTE.refund : PALETTE.ink).fontWeight(FontWeight.Medium)
        .constraintSize({ maxWidth: (this.layout().pageWidth - this.layout().padding * 2) * 0.48 })
        .maxLines(1).minFontSize(10).maxFontSize(16).textAlign(TextAlign.End)
    }.width('100%').padding({ top: 14, bottom: 14 }).border({ width: { bottom: 0.5 }, color: PALETTE.outline })
      .onClick(() => { if (this.openedSwipeId === entry.id) this.closeSwipeActions(); else { this.closeSwipeActions(); this.edit(entry); } })
      .accessibilityText(entry.title + '，' + kindName(entry.kind) + '，' + decimalAmount(entry.amountMinor) + '元，点击编辑')
  }
  @Builder
  homePage() {
    this.pageHeader('一语账', '把每一笔，轻轻记下')
    Row() {
      Text(Number(dateKey().slice(5, 7)) + '月' + Number(dateKey().slice(8)) + '日').fontSize(11).fontColor(PALETTE.muted)
      Blank()
      Text('我的账本').fontSize(10).fontColor(PALETTE.muted)
    }.width('100%').margin({ bottom: 14 })
    Column({ space: 18 }) {
      Row() {
        Text('本月净支出').fontSize(12).fontColor(PALETTE.onForestMuted); Blank()
        Text(Number(dateKey().slice(5, 7)) + '月 ⌄').fontSize(11).fontColor(PALETTE.onForestMuted)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 }).backgroundColor(PALETTE.heroAccent).borderRadius(7)
          .onClick(() => { this.mode = 'month'; this.periodOffset = 0; this.changeTab('stats'); })
      }.width('100%')
      Row({ space: 5 }) {
        Text('¥').fontSize(20).fontColor(PALETTE.onForestMuted).padding({ top: 10 })
        Text(formatMoney(this.monthSummary().netExpense)).fontSize(38).fontWeight(FontWeight.Medium).fontColor(PALETTE.onForest)
          .layoutWeight(1).maxLines(1).minFontSize('12vp').maxFontSize(38)
      }.width('100%')
      Divider().color(PALETTE.heroLine).strokeWidth(0.5)
      Row() {
        Column({ space: 8 }) {
          Text('↘ 本月收入').fontSize(10).fontColor(PALETTE.onForestMuted)
          Text(formatMoney(this.monthSummary().income)).fontSize(20).fontColor(PALETTE.onForest).width('100%').maxLines(1).minFontSize('10vp').maxFontSize(20)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Column({ space: 8 }) {
          Text('收支结余').fontSize(10).fontColor(PALETTE.onForestMuted)
          Text(formatMoney(this.monthSummary().balance)).fontSize(20).fontColor(PALETTE.onForest).width('100%').maxLines(1).minFontSize('10vp').maxFontSize(20)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      }.width('100%')
    }.width('100%').padding(24).backgroundColor(PALETTE.forest).borderRadius(26)
    this.sectionTitle('说一句，记一笔')
    Row({ space: 15 }) {
      Row() { this.icon('mic', 26, PALETTE.onForest) }
        .width(58).height(58).justifyContent(FlexAlign.Center).borderRadius(29).backgroundColor(PALETTE.forest)
      Column({ space: 7 }) {
        Text(this.starting ? '准备录音…' : '按住说话').fontSize(20).fontColor(PALETTE.forest).fontWeight(FontWeight.Medium)
        Text('松开生成账单，上滑取消').fontSize(10).fontColor(PALETTE.muted)
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      if (this.windowWidth / Math.max(1, this.fontScale) >= 350) { Row({ space: 3 }) {
        ForEach([12, 22, 29, 20, 35, 25, 16], (height: number, index: number) => {
          Rect().width(3).height(height).fill(PALETTE.muted).radius(2)
        }, (height: number, index: number) => String(index))
      } }
    }.width('100%').constraintSize({ minHeight: 102 }).padding(20).borderRadius(24).backgroundColor(PALETTE.sage)
      .border({ width: { bottom: 3 }, color: PALETTE.sageBorder }).onTouch((event: TouchEvent) => this.touchVoice(event))
      .id('voiceButton')
      .enabled(this.ready && !this.busy).accessibilityText('按住说话，松开生成账单，上滑取消')
    Text('试试说：“午餐吃了盖饭，花了15元”').fontSize(10).fontColor(PALETTE.muted).textAlign(TextAlign.Center)
      .width('100%').margin({ top: 12, bottom: 18 })
    Row({ space: 10 }) {
      Button() { Row({ space: 7 }) { this.icon('edit', 15, PALETTE.muted); Text('手动记账').fontSize(12).fontColor(PALETTE.muted); } }
        .layoutWeight(1).height(this.layout().controlHeight).backgroundColor(PALETTE.card).borderRadius(14).border({ width: 1, color: PALETTE.outline })
        .onClick(() => this.newEntry()).enabled(this.ready)
      Button() { Row({ space: 7 }) { this.icon('text', 15, PALETTE.muted); Text('文字记账').fontSize(12).fontColor(PALETTE.muted); } }
        .layoutWeight(1).height(this.layout().controlHeight).backgroundColor(PALETTE.card).borderRadius(14).border({ width: 1, color: PALETTE.outline })
        .onClick(() => { this.activeSheet = 'text'; this.textInput = ''; }).enabled(this.ready)
    }.width('100%')
    if (this.drafts.length > 0) {
      Text('有' + this.drafts.length + '条未完成记录，继续处理 ›').fontSize(12).fontColor(PALETTE.income).width('100%')
        .padding(14).margin({ top: 14 }).backgroundColor(PALETTE.field).borderRadius(12).onClick(() => this.activeSheet = 'unfinished')
    }
    this.sectionTitle('今天的记录', '查看账单')
    if (this.todayEntries().length === 0) { this.empty('今天，从第一笔开始', '按住说话，或者手动记下一笔。') }
    else { ForEach(this.todayEntries(), (entry: LedgerEntry) => { this.entryRow(entry) }, (entry: LedgerEntry) => this.entryRevision(entry)) }
  }
  @Builder
  kindChips(selected: string, includeAll: boolean, change: (kind: string) => void) {
    Scroll() {
      Row({ space: 8 }) {
        if (includeAll) {
          Text('全部').fontSize(12).fontColor(selected === 'all' ? PALETTE.onForest : PALETTE.muted)
            .padding({ left: 17, right: 17, top: 9, bottom: 9 }).backgroundColor(selected === 'all' ? PALETTE.forest : PALETTE.field)
            .borderRadius(20).onClick(() => change('all'))
        }
        ForEach(KINDS, (kind: string) => {
          Text(kindName(kind)).fontSize(12).fontColor(selected === kind ? PALETTE.onForest : PALETTE.muted)
            .padding({ left: 17, right: 17, top: 9, bottom: 9 }).backgroundColor(selected === kind ? PALETTE.forest : PALETTE.field)
            .borderRadius(20).onClick(() => change(kind))
        }, (kind: string) => kind + '_' + selected)
      }
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
  }
  @Builder
  ledgerControls() {
    this.pageHeader('账单', '每一笔生活，都有迹可循')
    Row({ space: 8 }) {
      Button({ type: ButtonType.Circle }) { this.icon('calendar', 19, this.dateFilter || this.drillStart ? PALETTE.forest : PALETTE.muted) }
        .width(44).height(44).backgroundColor(this.dateFilter || this.drillStart ? PALETTE.sage : Color.Transparent)
        .onClick(() => this.chooseLedgerDate()).id('ledgerDateButton').accessibilityText('选择日期查看账单')
      TextInput({ text: this.search, placeholder: this.layout().pageWidth < 330 ? '搜索账单' : '搜索名称、分类、金额', controller: this.searchController })
        .fontSize(13).fontColor(PALETTE.ink).backgroundColor(Color.Transparent).layoutWeight(1).padding(0).height(this.layout().controlHeight)
        .maxLength(200).enterKeyType(EnterKeyType.Search).cancelButton({ style: CancelButtonStyle.INPUT })
        .onChange((value: string) => this.updateLedgerSearch(value)).onSubmit(() => this.submitLedgerSearch()).id('ledgerSearchInput')
      Button({ type: ButtonType.Circle }) { this.icon('search', 19) }
        .width(44).height(44).backgroundColor(PALETTE.sage).onClick(() => this.submitLedgerSearch())
        .id('ledgerSearchButton').accessibilityText('搜索账单')
    }.width('100%').padding({ left: 8, right: 8 }).backgroundColor(PALETTE.field).borderRadius(15).margin({ bottom: 16 })
    this.kindChips(this.filterKind, true, (kind: string) => {
      this.closeSwipeActions(); this.filterKind = kind; this.rebuildLedgerView(); this.scrollLedgerTop();
    })
    Row({ space: 10 }) {
      Text(this.ledgerScope.label + (this.categoryFilter ? ' · ' + this.category(this.categoryFilter).name : ''))
        .fontSize(11).fontColor(PALETTE.muted).layoutWeight(1).maxLines(2).id('ledgerScopeLabel')
    }.width('100%').constraintSize({ minHeight: 28 }).margin({ top: 8 })
  }
  @Builder
  ledgerDateSheet() {
    Column({ space: 16 }) {
      Row({ space: 12 }) {
        Column().width(44).height(44)
        Text(this.ledgerPickerWeekday()).fontSize(22).fontColor(PALETTE.forest).fontWeight(FontWeight.Medium)
          .layoutWeight(1).textAlign(TextAlign.Center).maxLines(1).id('ledgerWeekdayTitle')
        Button({ type: ButtonType.Circle }) { this.icon('close', 19, PALETTE.muted) }
          .width(44).height(44).backgroundColor(Color.Transparent).onClick(() => this.dismissOverlay()).accessibilityText('取消选择日期')
      }.width('100%').height(Math.max(44, this.layout().controlHeight))
      DatePicker({ start: new Date(1900, 0, 1), end: new Date(2199, 11, 31), selected: new Date(this.ledgerPickerDate + 'T12:00:00') })
        .lunar(false).selectedTextStyle({ color: PALETTE.forest, font: { size: 18, weight: FontWeight.Medium } })
        .textStyle({ color: PALETTE.muted, font: { size: 16 } }).disappearTextStyle({ color: PALETTE.muted, font: { size: 14 } })
        .onDateChange((value: Date) => this.ledgerPickerDate = dateKey(value))
        .width('100%').height(this.ledgerPickerHeight()).id('ledgerDatePicker')
      Row({ space: 12 }) {
        Button('取消', { type: ButtonType.Normal }).layoutWeight(1).height(this.layout().controlHeight).fontSize(14)
          .fontColor(PALETTE.forest).backgroundColor(PALETTE.field).borderRadius(14).onClick(() => this.dismissOverlay()).id('cancelLedgerDate')
        Button('确定', { type: ButtonType.Normal }).layoutWeight(1).height(this.layout().controlHeight).fontSize(14)
          .fontColor(PALETTE.onForest).backgroundColor(PALETTE.forest).borderRadius(14).onClick(() => this.acceptLedgerDate()).id('acceptLedgerDate')
      }.width('100%')
    }.width('94%').constraintSize({ maxWidth: 560 }).padding(20).backgroundColor(PALETTE.background).borderRadius(28)
      .id('ledgerDateSheet')
  }
  @Builder
  ledgerDayHeader(day: LedgerDay) {
    if ((this.layout().pageWidth - this.layout().padding * 2) / Math.max(1, this.fontScale) < 260) {
      Column({ space: 6 }) {
        Text(ledgerDayLabel(day.date, this.browseToday)).fontSize(15).fontWeight(FontWeight.Medium).fontColor(PALETTE.forest)
        Text('支出：' + formatMoney(day.expenseMinor) + '元').fontSize(12).fontColor(PALETTE.forest)
          .width('100%').maxLines(1).minFontSize('10vp').maxFontSize(12).id('dailyExpense_' + day.date)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 18, bottom: 12 })
    } else {
      Row({ space: 12 }) {
        Text(ledgerDayLabel(day.date, this.browseToday)).fontSize(15).fontWeight(FontWeight.Medium).fontColor(PALETTE.forest)
          .layoutWeight(1).maxLines(1).minFontSize('12vp').maxFontSize(15)
        Text('支出：' + formatMoney(day.expenseMinor) + '元').fontSize(12).fontColor(PALETTE.forest).fontWeight(FontWeight.Medium)
          .layoutWeight(1).textAlign(TextAlign.End).maxLines(1).minFontSize('10vp').maxFontSize(12).id('dailyExpense_' + day.date)
      }.width('100%').margin({ top: 18, bottom: 12 })
    }
  }
  @Builder
  swipeDelete(entry: LedgerEntry) {
    Row() {
      Button('删除', { type: ButtonType.Normal }).fontSize(13).fontColor('#FFFFFF').backgroundColor(PALETTE.error)
        .width(Math.max(76, Math.min(120, 65 * this.fontScale))).height('100%').borderRadius(0)
        .enabled(!this.busy).onClick(() => this.confirmRemoveEntry(entry)).id('swipeDelete_' + entry.id)
        .accessibilityText('删除' + entry.title + '，' + decimalAmount(entry.amountMinor) + '元')
    }.width(Math.max(76, Math.min(120, 65 * this.fontScale)) + 16).height('100%').padding({ left: 16 })
  }
  @Builder
  ledgerPage() {
    List({ scroller: this.ledgerScroller }) {
      ListItem() { Column() { this.ledgerControls() }.width('100%').alignItems(HorizontalAlign.Start) }
      if (this.ledgerDays.length === 0) {
        ListItem() { Column() { this.empty('没有找到匹配账单', '试试更换关键词，或点击日历查看其他日期。') }.margin({ top: 24 }) }
      }
      ForEach(this.ledgerDays, (day: LedgerDay) => {
        ListItem() { this.ledgerDayHeader(day) }
        if (day.entries.length === 0) {
          ListItem() {
            Text(this.search || this.categoryFilter ? '当天没有符合筛选条件的账单' :
              this.filterKind === 'all' ? '当天还没有账单' : '当天没有' + kindName(this.filterKind) + '账单')
              .fontSize(12).fontColor(PALETTE.muted).width('100%').padding({ top: 12, bottom: 24 })
          }
        }
        ForEach(day.entries, (entry: LedgerEntry) => {
          ListItem() { this.entryRow(entry) }.id('bill_' + entry.id)
            .swipeAction({ end: { builder: () => this.swipeDelete(entry), onStateChange: (state: SwipeActionState) => {
              if (state === SwipeActionState.EXPANDED) this.openedSwipeId = entry.id;
              else if (state === SwipeActionState.COLLAPSED && this.openedSwipeId === entry.id) this.openedSwipeId = '';
            } }, edgeEffect: SwipeEdgeEffect.None })
        }, (entry: LedgerEntry) => this.entryRevision(entry))
      }, (day: LedgerDay) => day.revision)
      if (this.ledgerLoadedCount < this.ledgerMatchCount) {
        ListItem() {
          Button('已显示' + this.ledgerLoadedCount + '/' + this.ledgerMatchCount + '笔 · 继续加载', { type: ButtonType.Normal })
            .width('100%').height(48).fontSize(12).fontColor(PALETTE.muted).backgroundColor(Color.Transparent)
            .onClick(() => this.loadMoreLedger()).id('ledgerLoadMore')
        }
      }
      if ((this.ledgerScope.mode !== 'recent' || this.categoryFilter) && this.ledgerLoadedCount >= this.ledgerMatchCount) {
        ListItem() {
          Column() {
            Button('返回', { type: ButtonType.Normal }).width('100%').height(Math.max(50, this.layout().controlHeight)).fontSize(15)
              .backgroundColor(PALETTE.forest).fontColor(PALETTE.onForest).borderRadius(16)
              .onClick(() => this.resetLedgerScope()).id('ledgerResetScope')
          }.width('100%').padding({ top: 16, bottom: 12 })
        }
      }
    }.width('100%').layoutWeight(1).scrollBar(BarState.Off).id('ledgerList')
      .padding({ left: this.layout().padding, right: this.layout().padding, bottom: 30 })
      .onScrollStart(() => this.closeSwipeActions())
      .onReachEnd(() => this.loadMoreLedger())
  }
  @Builder
  statsPage() {
    this.pageHeader('收支统计', '看见数字，也看见生活')
    Row({ space: 4 }) {
      ForEach(['week', 'month', 'year'], (mode: string) => {
        Text(mode === 'week' ? '周' : mode === 'month' ? '月' : '年').fontSize(13).fontColor(this.mode === mode ? PALETTE.forest : PALETTE.muted)
          .fontWeight(this.mode === mode ? FontWeight.Medium : FontWeight.Normal).layoutWeight(1).textAlign(TextAlign.Center)
          .padding(11).backgroundColor(this.mode === mode ? PALETTE.card : Color.Transparent).borderRadius(11)
          .onClick(() => { this.mode = mode; this.periodOffset = 0; })
      }, (mode: string) => mode)
    }.width('100%').padding(4).backgroundColor(PALETTE.field).borderRadius(14)
    Row() {
      Button({ type: ButtonType.Circle }) { this.icon('back', 18, PALETTE.muted) }.backgroundColor(Color.Transparent).width(36).height(36).onClick(() => this.periodOffset--)
      Blank(); Text(this.period().label).fontSize(15).fontColor(PALETTE.ink); Blank()
      Button({ type: ButtonType.Circle }) { this.icon('chevron', 18, PALETTE.muted) }.backgroundColor(Color.Transparent).width(36).height(36).onClick(() => this.periodOffset++)
    }.width('100%').margin({ top: 16, bottom: 16 })
    Column({ space: 18 }) {
      Row({ space: 16 }) {
        Column({ space: 9 }) {
          Text('净支出').fontSize(11).fontColor(PALETTE.muted)
          Text(formatMoney(this.total().netExpense)).fontSize(29).fontColor(PALETTE.forest).fontWeight(FontWeight.Medium)
            .width('100%').maxLines(1).minFontSize('12vp').maxFontSize(29).id('statsExpenseAmount')
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Column({ space: 9 }) {
          Text('收入').fontSize(11).fontColor(PALETTE.muted)
          Text(formatMoney(this.total().income)).fontSize(24).fontColor(PALETTE.income)
            .width('100%').maxLines(1).minFontSize('12vp').maxFontSize(24).id('statsIncomeAmount')
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      }.width('100%')
      Divider().color(PALETTE.outline)
      Row() {
        Text('收支结余').fontSize(11).fontColor(PALETTE.muted); Blank()
        Text('¥ ' + formatMoney(this.total().balance)).fontSize(16).fontColor(PALETTE.ink).layoutWeight(1)
          .textAlign(TextAlign.End).maxLines(1).minFontSize('12vp').maxFontSize(16)
      }.width('100%')
      Text('支出 ' + formatMoney(this.total().expense) + ' · 退款 ' + formatMoney(this.total().refund))
        .fontSize(10).fontColor(PALETTE.muted).width('100%')
    }.width('100%').padding(22).backgroundColor(PALETTE.card).borderRadius(22).border({ width: 1, color: PALETTE.outline })
    BudgetCard({ progress: this.budgetProgress(), widthAvailable: this.layout().pageWidth - this.layout().padding * 2,
      fontScale: this.fontScale, onSetup: () => this.setupBudget() })
    this.sectionTitle('收支趋势')
    Column() {
      TrendChart({ points: trendPoints(this.entries, this.period()),
        onSelect: (day: string) => this.drill('', 'all', this.mode === 'year' ? day.slice(0, 7) : day) })
    }.width('100%').padding(14).backgroundColor(PALETTE.card).borderRadius(20)
    Row() {
      Text('分类排行').fontSize(16).fontColor(PALETTE.forest); Blank()
      Text('支出').fontSize(12).fontColor(this.rankingKind === 'expense' ? PALETTE.forest : PALETTE.muted)
        .padding(8).onClick(() => this.rankingKind = 'expense')
      Text('收入').fontSize(12).fontColor(this.rankingKind === 'income' ? PALETTE.income : PALETTE.muted)
        .padding(8).onClick(() => this.rankingKind = 'income')
    }.width('100%').margin({ top: 24, bottom: 10 })
    if (this.rankings().length === 0) { this.empty('这段时间还没有记录', '记下几笔，收支趋势就会在这里出现。') }
    ForEach(this.rankings(), (item: CategoryTotal) => {
      Column({ space: 10 }) {
        Row({ space: 10 }) {
          this.icon(item.category.icon, 18, item.category.color)
          Text(item.category.name).fontSize(13).fontColor(PALETTE.ink).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(Math.round(item.ratio * 100) + '%').fontSize(10).fontColor(PALETTE.muted); Blank()
          Text(formatMoney(item.amount) + ' ›').fontSize(15).fontColor(PALETTE.ink).constraintSize({ maxWidth: this.layout().pageWidth * 0.4 })
            .maxLines(1).minFontSize('12vp').maxFontSize(15)
        }.width('100%')
        Stack({ alignContent: Alignment.Start }) {
          Rect().width('100%').height(5).fill(PALETTE.field).radius(3)
          Rect().width(Math.max(1, item.ratio * 100) + '%').height(5).fill(item.category.color).radius(3)
        }.width('100%')
      }.width('100%').padding({ top: 13, bottom: 13 }).onClick(() => this.drill(item.category.id, this.rankingKind))
    }, (item: CategoryTotal) => item.category.id + '_' + item.amount + '_' + item.ratio + '_' + item.count + '_' + this.rankingKind + '_' + this.period().start)
    Text('净支出＝支出−退款；结余＝收入−净支出。转账、借还本金不计入收支。')
      .fontSize(10).fontColor(PALETTE.muted).lineHeight(18 * Math.max(1, this.fontScale)).margin({ top: 20 }).width('100%')
  }
  @Builder
  budgetSetup() {
    Text(this.budgetEditingMonth.slice(0, 4) + '年' + Number(this.budgetEditingMonth.slice(5)) + '月起生效')
      .fontSize(14).fontColor(PALETTE.forest).margin({ bottom: 16 })
    TextInput({ text: this.budgetInput, placeholder: '输入每月消费额度（元）' }).type(InputType.NUMBER_DECIMAL)
      .fontSize(22).height(this.layout().controlHeight + 12).backgroundColor(PALETTE.field).borderRadius(14)
      .maxLength(13).onChange((value: string) => { this.budgetInput = value; this.budgetError = ''; }).id('monthlyBudgetInput')
    Text('后续月份自动沿用，直到你再次调整。已单独设置过的月份保持自己的额度。')
      .fontSize(12).fontColor(PALETTE.muted).lineHeight(21 * Math.max(1, this.fontScale)).margin({ top: 14, bottom: 14 })
    if (this.budgetError) { Text(this.budgetError).fontSize(12).fontColor(PALETTE.error).margin({ bottom: 12 }) }
  }
  @Builder
  budgetSheet() {
    Column() {
      Column() { this.overlayHeader('设置月预算') }
        .width('100%').padding({ left: this.layout().padding, right: this.layout().padding, top: 14 })
      Scroll() {
        Column() { this.budgetSetup() }.width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: this.layout().padding, right: this.layout().padding, bottom: 8 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off).id('budgetScroll')
      Column() {
        Button(this.busy ? '正在保存…' : '保存预算').width('100%').height(Math.max(50, this.layout().controlHeight)).fontSize(15)
          .backgroundColor(PALETTE.forest).fontColor(PALETTE.onForest).enabled(!this.busy).onClick(() => this.saveBudget()).id('saveMonthlyBudget')
        if (this.budgetProgress().limit > 0) {
          Button('从该月起关闭预算').width('100%').height(this.layout().controlHeight).fontSize(12).fontColor(PALETTE.muted)
            .backgroundColor(Color.Transparent).enabled(!this.busy).onClick(() => this.saveBudget(true))
        }
      }.width('100%').padding({ left: this.layout().padding, right: this.layout().padding, top: 12, bottom: 12 })
        .backgroundColor(PALETTE.background).border({ width: { top: 0.5 }, color: PALETTE.outline })
    }.width('100%').constraintSize({ maxWidth: 600 }).height('94%').backgroundColor(PALETTE.background)
      .borderRadius({ topLeft: 28, topRight: 28 }).clip(true)
  }
  @Builder
  field(label: string, value: string, placeholder: string, change: (value: string) => void, numeric: boolean = false) {
    Column({ space: 6 }) {
      Text(label).fontSize(11).fontColor(PALETTE.muted)
      TextInput({ text: value, placeholder: placeholder }).fontSize(15).fontColor(PALETTE.ink).height(this.layout().controlHeight)
        .backgroundColor(PALETTE.field).borderRadius(13).padding({ left: 14, right: 14 })
        .type(numeric ? InputType.NUMBER_DECIMAL : InputType.Normal).onChange(change).maxLength(80).id('editorTitle')
    }.width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })
  }
  @Builder
  actionRow(icon: string, title: string, description: string, action: () => void) {
    Row({ space: 12 }) {
      this.icon(icon, 20, PALETTE.muted)
      Column({ space: 6 }) {
        Text(title).fontSize(14).fontColor(PALETTE.ink); Text(description).fontSize(10).fontColor(PALETTE.muted).lineHeight(17 * Math.max(1, this.fontScale))
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      this.icon('chevron', 16, PALETTE.muted)
    }.width('100%').padding({ top: 18, bottom: 18 }).border({ width: { bottom: 0.5 }, color: PALETTE.outline }).onClick(action)
  }
  @Builder
  settingsPage() {
    this.pageHeader('设置', '为随手记账，做好准备')
    this.sectionTitle('语音记账')
    Column({ space: 18 }) {
      Row({ space: 13 }) {
        Row() { this.icon('mic', 23, PALETTE.muted) }.width(46).height(46).justifyContent(FlexAlign.Center).backgroundColor(PALETTE.sage).borderRadius(15)
        Column({ space: 7 }) {
          Text(this.cloudKeyConfigured ? '语音记账已启用' : '开启语音记账').fontSize(16).fontColor(PALETTE.ink).fontWeight(FontWeight.Medium)
          Text(this.cloudKeyConfigured ? '个人 API Key 已加密保存' : '添加一次 API Key，随时开口记账').fontSize(11).fontColor(PALETTE.muted)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      }.width('100%')
      Button(this.cloudKeyConfigured ? '更换 API Key' : '添加 API Key').width('100%').height(this.layout().controlHeight).fontSize(13)
        .backgroundColor(this.cloudKeyConfigured ? PALETTE.sage : PALETTE.forest).fontColor(this.cloudKeyConfigured ? PALETTE.ink : PALETTE.onForest).borderRadius(14)
        .enabled(!this.busy).onClick(() => this.openKeySetup())
    }.width('100%').padding(20).backgroundColor(PALETTE.card).borderRadius(22)
    this.sectionTitle('数据与备份')
    this.actionRow('export', '导出完整备份', '包含当前账目、自定义分类和月预算', () => this.exportData(false))
    this.actionRow('ledger', '导出 CSV', '用表格软件查看、分析和分享账单', () => this.exportData(true))
    this.actionRow('import', '恢复备份', '合并导入，已有编号不覆盖', () => this.importData())
    this.sectionTitle('关于一语账')
    this.actionRow('shield', '隐私与数据说明', '账本留在本机，识别结果确认后保存', () => this.activeSheet = 'privacy')
    Text('一语账 0.7.0 · 试用版').fontSize(11).fontColor(PALETTE.muted).width('100%').textAlign(TextAlign.Center).margin({ top: 26 })
  }
  @Builder
  keySetup() {
    this.overlayHeader(this.cloudKeyConfigured ? '更换 API Key' : '开启语音记账')
    Text('填写自己的阿里云百炼 API Key，即可使用语音和文字智能记账。').fontSize(14).fontColor(PALETTE.muted).lineHeight(25 * Math.max(1, this.fontScale)).margin({ bottom: 24 })
    Text('百炼 API Key').fontSize(11).fontColor(PALETTE.muted).margin({ bottom: 9 })
    TextInput({ text: this.cloudKeyInput, placeholder: this.cloudKeyConfigured ? '填写新的密钥，原密钥不会回显' : '粘贴你的百炼 API Key' })
      .fontSize(14).height(Math.max(52, this.layout().controlHeight)).type(InputType.Password).showPasswordIcon(false).maxLength(1024)
      .backgroundColor(PALETTE.field).borderRadius(14).onChange((value: string) => this.cloudKeyInput = value)
    Hyperlink('https://help.aliyun.com/zh/model-studio/get-api-key', '如何获取 API Key ›')
      .color(PALETTE.muted).margin({ top: 16, bottom: 24 })
    Text('密钥由鸿蒙系统加密保存在手机，不加入账本备份。识别用量计入你自己的百炼账号。')
      .fontSize(11).fontColor(PALETTE.muted).lineHeight(21 * Math.max(1, this.fontScale)).margin({ bottom: 22 })
    if (this.keyState) { Text(this.keyState).fontSize(12).fontColor(PALETTE.income).lineHeight(22 * Math.max(1, this.fontScale)).margin({ bottom: 18 }) }
    Button(this.busy ? '正在验证…' : '保存并启用').width('100%').height(Math.max(50, this.layout().controlHeight)).fontSize(15)
      .backgroundColor(PALETTE.forest).fontColor(PALETTE.onForest).borderRadius(16).enabled(!this.busy && this.cloudKeyInput.trim().length > 0)
      .onClick(() => this.saveCloudKey())
    if (this.cloudKeyConfigured) {
      Text('移除已保存的密钥').fontSize(12).fontColor(PALETTE.income).padding({ top: 22, bottom: 10 }).width('100%').textAlign(TextAlign.Center)
        .onClick(() => AlertDialog.show({ title: '移除百炼密钥？', message: '账本会保留。再次使用智能记账前需要重新配置密钥。',
          primaryButton: { value: '保留', action: () => {} }, secondaryButton: { value: '移除', action: () => this.removeCloudKey() } }))
    }
  }
  @Builder
  unfinishedRecords() {
    this.overlayHeader('未完成记录')
    Text('待确认账单可以继续保存。识别失败的录音仅暂存24小时，到期后在下次打开或回到应用时自动清理。')
      .fontSize(12).fontColor(PALETTE.muted).lineHeight(22 * Math.max(1, this.fontScale)).margin({ bottom: 18 })
    if (this.drafts.length === 0) { this.empty('已经全部处理完', '新的记账内容，随时说给我听。') }
    ForEach(this.drafts, (draft: AudioDraft) => {
      Row({ space: 10 }) {
        this.icon('mic', 20, PALETTE.muted)
        Column({ space: 5 }) {
          Text(draft.resultJson ? '待确认账单' : '识别未完成').fontSize(14).fontColor(PALETTE.ink)
          Text(draft.referenceDate).fontSize(10).fontColor(PALETTE.muted)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Button(draft.resultJson ? '继续' : '重试').fontSize(11).height(32).backgroundColor(PALETTE.sage).fontColor(PALETTE.muted).onClick(() => this.retryDraft(draft)).enabled(!this.busy)
        Button({ type: ButtonType.Circle }) { this.icon('close', 15, PALETTE.muted) }
          .height(30).width(30).backgroundColor(PALETTE.field).onClick(() => {
            AlertDialog.show({ title: '移除这条未完成记录？', message: '尚未确认的内容将移除，已保存的账单会保留。',
              primaryButton: { value: '保留', action: () => {} },
              secondaryButton: { value: '删除', action: () => { this.deleteDraft(draft); } } });
          }).enabled(!this.busy)
      }.width('100%').padding({ top: 14, bottom: 14 })
    }, (draft: AudioDraft) => draft.id + '_' + draft.savedCount + '_' + (draft.resultJson ? 'ready' : 'retry'))
  }
  @Builder
  overlayHeader(title: string) {
    Row({ space: 12 }) {
      Text(title).fontSize(22).fontColor(PALETTE.forest).fontWeight(FontWeight.Medium).layoutWeight(1).maxLines(2)
      Button({ type: ButtonType.Circle }) { this.icon('close', 19, PALETTE.muted) }
        .width(44).height(44).backgroundColor(PALETTE.sage).onClick(() => this.dismissOverlay()).enabled(!this.busy).accessibilityText('关闭')
    }.width('100%').padding({ top: 4, bottom: 22 })
  }
  @Builder
  editor() {
    if (this.proposalText || this.transcript) {
      Text('“' + (this.proposalText || this.transcript) + '”').fontSize(12).fontColor(PALETTE.muted).width('100%').padding(12)
        .maxLines(this.transcriptExpanded ? 20 : 2).textOverflow({ overflow: TextOverflow.Ellipsis })
        .backgroundColor(PALETTE.sage).borderRadius(13).margin({ bottom: 12 })
        .onClick(() => this.transcriptExpanded = !this.transcriptExpanded)
        .accessibilityText('这笔原话，点击展开或收起：' + (this.proposalText || this.transcript)).id('proposalSource')
    }
    if (!this.editing && this.pendingSource !== 'manual' && this.proposalTotal > 1) {
      Text('第' + this.proposalNumber + '笔 / 共' + this.proposalTotal + '笔').fontSize(12).fontColor(PALETTE.income).margin({ bottom: 14 }).id('proposalProgress')
    }
    ForEach(this.editorIssues, (issue: string) => { Text('· ' + issue).fontSize(11).fontColor(PALETTE.error).margin({ bottom: 8 }) }, (issue: string) => issue)
    this.kindChips(this.editorKind, false, (kind: string) => this.setEditorKind(kind))
    Column({ space: 2 }) {
      Text('金额 · 人民币元').fontSize(11).fontColor(PALETTE.muted)
      Row({ space: 8 }) {
        Text('¥').fontSize(Math.min(27 * Math.max(1, this.fontScale), 32) + 'vp').fontColor(PALETTE.muted)
        TextInput({ text: this.editorAmount, placeholder: '0.00' }).fontSize(this.amountFont() + 'vp').placeholderFont({ size: this.amountFont() + 'vp', weight: FontWeight.Medium })
          .height(Math.max(52, this.layout().controlHeight, this.amountFont() * 1.25)).fontWeight(FontWeight.Medium)
          .fontColor(PALETTE.forest).backgroundColor(Color.Transparent).type(InputType.NUMBER_DECIMAL).padding(0)
          .layoutWeight(1).maxLength(13).onChange((value: string) => this.editorAmount = value).id('editorAmount')
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 12, bottom: 10 })
    this.field('名称', this.editorTitle, '这笔钱花在哪里？', (value: string) => this.editorTitle = value)
    this.categoryPicker()
    Row({ space: 8 }) {
      this.icon('calendar', 17, PALETTE.muted)
      Text('日期').fontSize(12).fontColor(PALETTE.muted); Blank()
      Text(this.editorDate + ' ›').fontSize(13).fontColor(PALETTE.ink).maxLines(1).layoutWeight(1).textAlign(TextAlign.End).minFontSize(10).maxFontSize(13)
    }.width('100%').constraintSize({ minHeight: this.layout().controlHeight }).padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(PALETTE.field).borderRadius(13).margin({ bottom: 12 }).id('editorDate').onClick(() => {
        DatePickerDialog.show({ start: new Date(1900, 0, 1), end: new Date(2199, 11, 31),
          selected: new Date(this.editorDate + 'T12:00:00'),
          onAccept: (value: DatePickerResult) => {
            if (value.year !== undefined && value.month !== undefined && value.day !== undefined)
              this.editorDate = String(value.year) + '-' + String(value.month + 1).padStart(2, '0') + '-' + String(value.day).padStart(2, '0');
          } });
      })
    Row({ space: 8 }) {
      this.icon('edit', 17, PALETTE.muted)
      TextInput({ text: this.editorNote, placeholder: '备注（可选）' }).fontSize(13).fontColor(PALETTE.ink).layoutWeight(1)
        .height(this.layout().controlHeight).backgroundColor(Color.Transparent).padding(0).maxLength(500)
        .onChange((value: string) => this.editorNote = value).id('editorNote').accessibilityText('备注，可选')
    }.width('100%').padding({ left: 14, right: 14 }).backgroundColor(PALETTE.field).borderRadius(13).margin({ bottom: 12 })
    if (this.editorKind === 'transfer' || this.editorKind === 'debt') {
      Text('这笔记录不会计入收入或支出统计。').fontSize(11).fontColor(PALETTE.income).margin({ bottom: 18 })
    }
    if (this.editing) {
      Button('删除这笔账单').fontSize(12).fontColor(PALETTE.error).backgroundColor(Color.Transparent).width('100%').height(44)
        .enabled(!this.busy).onClick(() => this.confirmRemoveEntry())
    }
  }
  @Builder
  categoryPicker() {
    Row({ space: 8 }) {
      Text('分类').fontSize(11).fontColor(PALETTE.muted)
      Blank()
      Text('已选 · ' + this.category(this.editorCategory).name).fontSize(11).fontColor(PALETTE.ink).maxLines(2).textAlign(TextAlign.End).layoutWeight(1)
    }.width('100%').margin({ bottom: 8 })
    Row({ space: 6 }) {
      ForEach(this.quickCategories(), (category: Category) => {
        Button() {
          Row({ space: 5 }) {
            this.icon(category.icon, 15, this.editorCategory === category.id ? PALETTE.forest : PALETTE.muted)
            Text(category.name).fontSize(12).fontColor(this.editorCategory === category.id ? PALETTE.forest : PALETTE.muted)
              .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.Center)
          }.width('100%')
        }.layoutWeight(1).height(this.layout().controlHeight).padding({ left: 8, right: 8 })
          .backgroundColor(this.editorCategory === category.id ? PALETTE.sage : PALETTE.field).borderRadius(13)
          .border({ width: this.editorCategory === category.id ? 1 : 0, color: PALETTE.sageBorder })
          .onClick(() => this.selectCategory(category.id)).accessibilityText(category.name + (this.editorCategory === category.id ? '，已选' : '，点击选择'))
      }, (category: Category) => category.id + '_' + this.editorCategory)
      Button() {
        Row({ space: 4 }) {
          this.icon('dots', 15, PALETTE.muted)
          Text(this.categoriesExpanded ? '收起' : '更多').fontSize(12).fontColor(PALETTE.muted).maxLines(1)
        }.justifyContent(FlexAlign.Center)
      }.layoutWeight(1).height(this.layout().controlHeight).padding({ left: 5, right: 5 }).backgroundColor(PALETTE.field).borderRadius(13)
        .onClick(() => { this.categoriesExpanded = !this.categoriesExpanded; this.creatingCategory = false; }).id('moreCategories')
    }.width('100%').margin({ bottom: 14 })
    if (this.categoriesExpanded) {
      if (!this.creatingCategory) {
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.editorCategories(), (category: Category) => {
          Column({ space: 7 }) {
            this.icon(category.icon, 21, this.editorCategory === category.id ? PALETTE.forest : PALETTE.muted)
            Text(category.name).fontSize(12).fontColor(this.editorCategory === category.id ? PALETTE.forest : PALETTE.muted)
              .maxLines(2).textAlign(TextAlign.Center).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.width((100 / this.layout().categoryColumns) + '%').padding(8).constraintSize({ minHeight: 76 })
            .backgroundColor(this.editorCategory === category.id ? PALETTE.sage : Color.Transparent).borderRadius(14)
            .onClick(() => this.selectCategory(category.id)).accessibilityText(category.name + '，点击选择')
            .gesture(LongPressGesture().onAction(() => this.offerRemoveCategory(category)))
        }, (category: Category) => category.id + '_' + this.editorCategory)
        Column({ space: 7 }) {
          this.icon('plus', 21, PALETTE.muted); Text('自定义').fontSize(12).fontColor(PALETTE.muted)
        }.width((100 / this.layout().categoryColumns) + '%').padding(8).constraintSize({ minHeight: 76 })
          .onClick(() => { this.creatingCategory = true; this.customCategoryName = ''; this.customCategoryError = ''; }).id('customCategory')
      }.width('100%').padding(4).backgroundColor(PALETTE.field).borderRadius(16).margin({ bottom: 14 })
      if (this.customCategories.some((item: CustomCategory) => item.kind === categoryScope(this.editorKind))) {
        Text('长按自定义分类，可移除未使用的分类').fontSize(10).fontColor(PALETTE.muted).margin({ bottom: 12 })
      }
      }
      if (this.creatingCategory) {
        Column({ space: 10 }) {
          Row() {
            Text('新建' + kindName(this.editorKind) + '分类').fontSize(13).fontColor(PALETTE.ink).layoutWeight(1)
            Text('取消').fontSize(12).fontColor(PALETTE.muted).padding(8).onClick(() => this.creatingCategory = false)
          }.width('100%')
          TextInput({ text: this.customCategoryName, placeholder: '例如：宠物、健身（1至12字）', controller: this.customNameController }).fontSize(14)
            .height(this.layout().controlHeight).backgroundColor(PALETTE.sage).maxLength(12)
            .onChange((value: string) => { this.customCategoryName = value; this.customCategoryError = ''; }).id('customCategoryName')
          if (this.customCategoryError) { Text(this.customCategoryError).fontSize(12).fontColor(PALETTE.error) }
        }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(PALETTE.field).borderRadius(16).margin({ bottom: 14 })
      }
    }
  }
  @Builder
  editorSheet() {
    Column() {
      Column() { this.overlayHeader(this.editing ? '编辑账单' : this.pendingSource === 'manual' ? '手动记一笔' : '确认这一笔') }
        .width('100%').padding({ left: this.layout().padding, right: this.layout().padding, top: 14 })
      Scroll(this.editorScroll) {
        Column() { this.editor() }.width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: this.layout().padding, right: this.layout().padding, bottom: 8 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off).id('editorScroll')
      Column() {
        Button(this.busy ? '保存中…' : this.creatingCategory ? '创建并选用' : this.queuedCount > 0 ? '保存，确认下一笔' : '确认保存').width('100%')
          .height(Math.max(50, this.layout().controlHeight)).fontSize(15).backgroundColor(PALETTE.forest).fontColor(PALETTE.onForest).borderRadius(16)
          .enabled(!this.busy && (!this.creatingCategory || this.customCategoryName.trim().length > 0))
          .onClick(() => { if (this.creatingCategory) this.createCategory(); else this.save(); }).id('confirmSave')
      }.width('100%').padding({ left: this.layout().padding, right: this.layout().padding, top: 12, bottom: 12 })
        .backgroundColor(PALETTE.background).border({ width: { top: 0.5 }, color: PALETTE.outline })
    }.width('100%').constraintSize({ maxWidth: 600 }).height('94%').backgroundColor(PALETTE.background)
      .borderRadius({ topLeft: 28, topRight: 28 }).clip(true)
  }
  @Builder
  textEditor() {
    this.overlayHeader('用文字，记一笔')
    Text('像聊天一样写下消费或收入，可一次描述多笔。').fontSize(12).fontColor(PALETTE.muted).lineHeight(21 * Math.max(1, this.fontScale)).margin({ bottom: 18 })
    TextArea({ text: this.textInput, placeholder: '中午吃了辣椒炒肉盖饭15元，下午坐地铁8元' })
      .height(180).fontSize(16).fontColor(PALETTE.ink).backgroundColor(PALETTE.field).borderRadius(18).padding(18)
      .maxLength(2000).onChange((value: string) => this.textInput = value)
    Text('解析后逐笔确认，不会直接计入账本。').fontSize(11).fontColor(PALETTE.muted).margin({ top: 15, bottom: 26 })
    Button(this.busy ? '正在解析…' : '生成待确认账单').width('100%').height(Math.max(50, this.layout().controlHeight)).fontSize(15)
      .backgroundColor(PALETTE.forest).fontColor(PALETTE.onForest).borderRadius(16).enabled(!this.busy).onClick(() => this.parseText())
  }
  @Builder
  voicePanel() {
    Column({ space: 22 }) {
      Text(this.recording ? this.cancelVoice ? '松开取消' : '正在听你说…' : '正在整理账单…')
        .fontSize(24).fontColor(PALETTE.onForest).fontWeight(FontWeight.Medium)
      Text(this.recording ? this.seconds + ' / 30 秒' : '请稍候，识别结果需要你确认').fontSize(12).fontColor(PALETTE.onForestMuted)
      Row({ space: 5 }) {
        ForEach([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10], (index: number) => {
          Rect().width(5).height(14 + this.voiceLevel * (45 - Math.abs(index - 5) * 6)).fill(PALETTE.onForestMuted).radius(3)
        }, (index: number) => String(index))
      }.height(64)
      Text(this.transcript || '按住说话，松开生成账单').fontSize(17).fontColor(PALETTE.onForest).lineHeight(28 * Math.max(1, this.fontScale)).textAlign(TextAlign.Center).width('100%')
      Text(this.recording ? '上滑取消 · 松开结束' : '识别结果需要你确认后才会保存').fontSize(11).fontColor(PALETTE.onForestMuted)
    }.width('100%').padding(30).backgroundColor(this.cancelVoice ? PALETTE.error : PALETTE.forest).borderRadius(28)
  }
  @Builder
  privacy() {
    this.overlayHeader('隐私与数据')
    Text('账本保存在手机应用私有数据库。第一版不需要账号，不读取微信、支付宝或银行卡账单。卸载应用可能删除本地数据，请定期导出完整备份。')
      .fontSize(14).fontColor(PALETTE.muted).lineHeight(26 * Math.max(1, this.fontScale)).margin({ bottom: 22 })
    Text('删除账单会同步移除账本记录、统计引用和已确认的语音缓存副本，应用内不设回收站。尚未确认的其他账单保留。已导出到文件的独立副本不受影响；新导出文件只包含当前账单。')
      .fontSize(14).fontColor(PALETTE.muted).lineHeight(26 * Math.max(1, this.fontScale)).margin({ bottom: 22 })
    Text('使用语音和文字智能记账时，录音、描述及自定义分类名称通过加密网络直接发送至阿里云百炼，用于识别和提取账目信息。服务商的数据处理规则以其服务条款为准。')
      .fontSize(14).fontColor(PALETTE.muted).lineHeight(26 * Math.max(1, this.fontScale)).margin({ bottom: 22 })
    Text('手机直连使用你自己的百炼密钥，保存在鸿蒙系统的关键资产加密存储中，仅在手机解锁时读取，不加入备份，也不内置到安装包。可以在设置中更换或移除。')
      .fontSize(14).fontColor(PALETTE.muted).lineHeight(26 * Math.max(1, this.fontScale)).margin({ bottom: 22 })
    Text('识别结果逐笔确认后才计入账本。未识别到记账内容或未听清的录音自动删除；成功提取后删除录音，只保留待确认账单。识别失败或中断的录音仅在手机私有目录暂存24小时，到期后在下次打开或回到应用时清理，也可在首页提前移除。JSON/CSV备份不包含录音和 API Key。')
      .fontSize(14).fontColor(PALETTE.muted).lineHeight(26 * Math.max(1, this.fontScale))
  }
  build() {
    Stack({ alignContent: this.activeSheet === 'ledger-date' ? Alignment.Center : Alignment.Bottom }) {
      Column() {
        if (this.tab === 'ledger') { this.ledgerPage() }
        else { Scroll(this.mainScroller) {
          Column() {
            if (this.tab === 'home') { this.homePage() }
            if (this.tab === 'stats') { this.statsPage() }
            if (this.tab === 'settings') { this.settingsPage() }
          }.width('100%').padding({ left: this.layout().padding, right: this.layout().padding, bottom: 30 }).alignItems(HorizontalAlign.Start)
        }.layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Off).id('mainScroll')
          .enableScrollInteraction(!this.fingerDown && !this.starting && !this.recording && this.activeSheet !== 'voice')
          .onGestureJudgeBegin((gesture: GestureInfo, event: BaseGestureEvent) => {
            // Reject the parent's built-in pan before it competes with a voice-button touch sequence.
            return this.fingerDown || this.starting || this.recording || this.activeSheet === 'voice' ? GestureJudgeResult.REJECT : GestureJudgeResult.CONTINUE;
          }) }
        Row({ space: 4 }) {
          ForEach(['home', 'ledger', 'stats'], (tab: string) => {
            Button() {
              Row({ space: 7 }) {
                this.icon(tab === 'home' ? 'mic' : tab === 'ledger' ? 'ledger' : 'chart', 17, this.tab === tab ? PALETTE.forest : PALETTE.muted)
                Text(tab === 'home' ? '记账' : tab === 'ledger' ? '账单' : '统计').fontSize(12).fontColor(this.tab === tab ? PALETTE.forest : PALETTE.muted)
              }.justifyContent(FlexAlign.Center).width('100%')
            }.layoutWeight(1).height(this.layout().controlHeight).backgroundColor(this.tab === tab ? PALETTE.sage : Color.Transparent).borderRadius(15)
              .onClick(() => this.changeTab(tab)).enabled(!this.busy)
          }, (tab: string) => tab)
        }.width('90%').padding(8).backgroundColor(PALETTE.card).borderRadius(24).margin({ bottom: 10 })
      }.width('100%').constraintSize({ maxWidth: 720 }).height('100%')
      if (this.activeSheet === 'voice') {
        // Hit testing passes through so the original press still receives move/up events.
        Column() { this.voicePanel() }.width('100%').constraintSize({ maxWidth: 600 }).padding(this.layout().padding).margin({ bottom: 90 }).hitTestBehavior(HitTestMode.Transparent)
      }
      if (this.busy && (!this.activeSheet || this.activeSheet === 'voice')) {
        Column().width('100%').height('100%').backgroundColor(Color.Transparent).hitTestBehavior(HitTestMode.Block)
      }
      if (this.activeSheet && this.activeSheet !== 'voice') {
        Column().width('100%').height('100%').backgroundColor('#800D2117').onClick(() => this.dismissOverlay())
        if (this.activeSheet === 'edit') { this.editorSheet() }
        else if (this.activeSheet === 'budget') { this.budgetSheet() }
        else if (this.activeSheet === 'ledger-date') { this.ledgerDateSheet() }
        else { Scroll() {
          Column() {
            if (this.activeSheet === 'text') { this.textEditor() }
            if (this.activeSheet === 'privacy') { this.privacy() }
            if (this.activeSheet === 'api-key') { this.keySetup() }
            if (this.activeSheet === 'unfinished') { this.unfinishedRecords() }
          }.width('100%').alignItems(HorizontalAlign.Start).padding(this.layout().padding)
        }.width('100%').constraintSize({ maxWidth: 600 }).height('94%').align(Alignment.TopStart).backgroundColor(PALETTE.background).borderRadius({ topLeft: 30, topRight: 30 }).scrollBar(BarState.Off) }
      }
    }.width('100%').height('100%').backgroundColor(PALETTE.background)
      .onAreaChange((oldArea: Area, area: Area) => { if (Number(area.width) > 0) this.windowWidth = Number(area.width); })
  }
}
