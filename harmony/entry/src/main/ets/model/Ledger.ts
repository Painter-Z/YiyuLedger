// Pure domain code, shared by the Harmony client and the interactive design preview.
// Amounts are positive integer CNY cents; direction is represented by `kind`.
export interface Category {
  id: string;
  name: string;
  icon: string;
  color: string;
  background: string;
}

export interface CustomCategory {
  id: string;
  name: string;
  kind: string;
}

export interface MonthlyBudget {
  month: string;
  amountMinor: number;
}

export interface LedgerEntry {
  id: string;
  kind: string;
  amountMinor: number;
  currency: string;
  categoryId: string;
  title: string;
  occurredOn: string;
  timeHint: string;
  note: string;
  source: string;
  groupId: string;
  linkedEntryId: string;
  createdAt: number;
  updatedAt: number;
  deletedAt: number;
}

export interface Period {
  start: string;
  end: string;
  label: string;
  mode: string;
}

export interface Summary {
  expense: number;
  income: number;
  refund: number;
  netExpense: number;
  balance: number;
  count: number;
}

export interface CategoryTotal {
  category: Category;
  amount: number;
  ratio: number;
  count: number;
}

export interface TrendPoint {
  date: string;
  label: string;
  expense: number;
  income: number;
  refund: number;
}

export interface BackupDocument {
  format: string;
  version: number;
  exportedAt: string;
  entries: LedgerEntry[];
  categories: CustomCategory[];
  monthlyBudgets: MonthlyBudget[];
}

export const CATEGORIES: Category[] = [
  { id: 'food', name: '餐饮', icon: 'food', color: '#547347', background: '#EAF0DD' },
  { id: 'transport', name: '交通', icon: 'car', color: '#587D8D', background: '#E7EFF2' },
  { id: 'shopping', name: '购物', icon: 'bag', color: '#9C775B', background: '#F2EADF' },
  { id: 'daily', name: '日用', icon: 'basket', color: '#8B8670', background: '#ECEBE3' },
  { id: 'housing', name: '居住', icon: 'home', color: '#688477', background: '#E6EDE7' },
  { id: 'entertainment', name: '娱乐', icon: 'play', color: '#9581A7', background: '#EEE8F2' },
  { id: 'health', name: '医疗', icon: 'heart', color: '#AD7971', background: '#F5E7E2' },
  { id: 'education', name: '学习', icon: 'book', color: '#7A86A0', background: '#E9EDF5' },
  { id: 'travel', name: '旅行', icon: 'plane', color: '#638D89', background: '#E3EFEC' },
  { id: 'social', name: '人情', icon: 'gift', color: '#B08762', background: '#F4EDE0' },
  { id: 'salary', name: '工资', icon: 'wallet', color: '#AE8252', background: '#F5ECD9' },
  { id: 'bonus', name: '奖金', icon: 'gift', color: '#AE8252', background: '#F5ECD9' },
  { id: 'freelance', name: '兼职', icon: 'briefcase', color: '#AE8252', background: '#F5ECD9' },
  { id: 'investment', name: '利息', icon: 'trend', color: '#AE8252', background: '#F5ECD9' },
  { id: 'transfer', name: '转账', icon: 'swap', color: '#7F8B88', background: '#E9EDEA' },
  { id: 'debt', name: '往来', icon: 'swap', color: '#7F8B88', background: '#E9EDEA' },
  { id: 'other', name: '其他', icon: 'dots', color: '#8C9183', background: '#ECEEE6' }
];

export const KINDS: string[] = ['expense', 'income', 'refund', 'transfer', 'debt'];

export function kindName(kind: string): string {
  if (kind === 'income') return '收入';
  if (kind === 'refund') return '退款';
  if (kind === 'transfer') return '转账';
  if (kind === 'debt') return '往来';
  return '支出';
}

export function categoryScope(kind: string): string { return kind === 'refund' ? 'expense' : kind; }

export function categoryCatalog(custom: CustomCategory[] = []): Category[] {
  return CATEGORIES.concat(custom.map((item: CustomCategory): Category => {
    return { id: item.id, name: item.name, icon: 'dots', color: '#63806A', background: '#E5EEE4' };
  }));
}

export function categoryById(id: string, custom: CustomCategory[] = []): Category {
  const result: Category | undefined = categoryCatalog(custom).find((item: Category) => item.id === id);
  return result === undefined ? CATEGORIES[CATEGORIES.length - 1] : result;
}

export function categoriesForKind(kind: string, custom: CustomCategory[] = []): Category[] {
  const extra: Category[] = categoryCatalog(custom.filter((item: CustomCategory) => item.kind === categoryScope(kind))).slice(CATEGORIES.length);
  if (kind === 'income') {
    return CATEGORIES.filter((item: Category) => ['salary', 'bonus', 'freelance', 'investment', 'social', 'other'].includes(item.id)).concat(extra);
  }
  if (kind === 'transfer') return CATEGORIES.filter((item: Category) => item.id === 'transfer').concat(extra);
  if (kind === 'debt') return CATEGORIES.filter((item: Category) => item.id === 'debt').concat(extra);
  return CATEGORIES.filter((item: Category) => !['salary', 'bonus', 'freelance', 'investment', 'transfer', 'debt'].includes(item.id)).concat(extra);
}

export function validateCategoryCatalog(custom: CustomCategory[]): string {
  if (!Array.isArray(custom) || custom.length > 100) return '自定义分类最多100个';
  const ids: string[] = [];
  const names: string[] = [];
  for (const item of custom) {
    if (!item || typeof item.id !== 'string' || !/^custom_[a-zA-Z0-9_-]{1,64}$/.test(item.id)) return '自定义分类编号不正确';
    if (!['expense', 'income', 'transfer', 'debt'].includes(item.kind)) return '自定义分类类型不正确';
    if (typeof item.name !== 'string' || !item.name.trim() || item.name !== item.name.trim() || item.name.length > 12 || /[\x00-\x1f\x7f]/.test(item.name)) return '分类名称请填写1至12个字';
    if (['更多', '收起', '自定义'].includes(item.name)) return '请使用具体的分类名称';
    const key: string = item.kind + '_' + item.name.toLowerCase();
    if (ids.includes(item.id) || names.includes(key) || categoriesForKind(item.kind).some((base: Category) => base.name === item.name)) return '该类型下已有同名分类';
    ids.push(item.id); names.push(key);
  }
  return '';
}

// Stable identifiers must keep their meaning when a backup is merged into an existing book.
export function mergeCategoryCatalog(existing: CustomCategory[], incoming: CustomCategory[]): CustomCategory[] {
  const combined: CustomCategory[] = existing.slice();
  for (const item of incoming) {
    const found: CustomCategory | undefined = existing.find((current: CustomCategory) => current.id === item.id);
    if (found && (found.name !== item.name || found.kind !== item.kind)) throw new Error('备份分类编号冲突，原账本已保留');
    if (!found) combined.push(item);
  }
  const error: string = validateCategoryCatalog(combined);
  if (error) throw new Error(error);
  return combined;
}

export function parseMinor(value: string): number {
  const text: string = value.trim();
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(text)) return -1;
  const parts: string[] = text.split('.');
  const amount: number = Number(parts[0]) * 100 + Number(((parts[1] || '') + '00').slice(0, 2));
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > 999999999999) return -1;
  return amount;
}

export function decimalAmount(minor: number): string {
  if (!Number.isSafeInteger(minor)) return '0.00';
  const sign: string = minor < 0 ? '-' : '';
  const amount: number = Math.abs(minor);
  return sign + String(Math.floor(amount / 100)) + '.' + String(amount % 100).padStart(2, '0');
}

export function formatMoney(minor: number): string {
  const parts: string[] = decimalAmount(minor).split('.');
  return parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + parts[1];
}

export function dateKey(date: Date = new Date()): string {
  return String(date.getFullYear()).padStart(4, '0') + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
}

function utcKey(date: Date): string {
  return String(date.getUTCFullYear()).padStart(4, '0') + '-' + String(date.getUTCMonth() + 1).padStart(2, '0') + '-' + String(date.getUTCDate()).padStart(2, '0');
}

export function isDateKey(value: string): boolean {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed: Date = new Date(value + 'T00:00:00Z');
  return !Number.isNaN(parsed.getTime()) && utcKey(parsed) === value && value >= '1900-01-01' && value <= '2199-12-31';
}

export function shiftDays(value: string, delta: number): string {
  const parsed: Date = new Date(value + 'T00:00:00Z');
  parsed.setUTCDate(parsed.getUTCDate() + delta);
  return utcKey(parsed);
}

export function periodFor(mode: string, reference: string, offset: number = 0): Period {
  const date: Date = new Date(reference + 'T00:00:00Z');
  if (mode === 'week') {
    const weekday: number = (date.getUTCDay() + 6) % 7;
    const start: string = shiftDays(reference, -weekday + offset * 7);
    const end: string = shiftDays(start, 6);
    return { start: start, end: end, label: Number(start.slice(5, 7)) + '.' + Number(start.slice(8)) + ' — ' + Number(end.slice(5, 7)) + '.' + Number(end.slice(8)), mode: mode };
  }
  if (mode === 'year') {
    const year: number = date.getUTCFullYear() + offset;
    return { start: year + '-01-01', end: year + '-12-31', label: year + '年', mode: mode };
  }
  const first: Date = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + offset, 1));
  const last: Date = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0));
  return { start: utcKey(first), end: utcKey(last), label: first.getUTCFullYear() + '年' + (first.getUTCMonth() + 1) + '月', mode: 'month' };
}

export function inPeriod(entry: LedgerEntry, period: Period): boolean {
  return entry.deletedAt === 0 && entry.currency === 'CNY' && entry.occurredOn >= period.start && entry.occurredOn <= period.end;
}

export function summarize(entries: LedgerEntry[], period: Period): Summary {
  let expense: number = 0;
  let income: number = 0;
  let refund: number = 0;
  let count: number = 0;
  entries.forEach((entry: LedgerEntry) => {
    if (!inPeriod(entry, period)) return;
    count++;
    if (entry.kind === 'expense') expense += entry.amountMinor;
    if (entry.kind === 'income') income += entry.amountMinor;
    if (entry.kind === 'refund') refund += entry.amountMinor;
  });
  return { expense: expense, income: income, refund: refund, netExpense: expense - refund, balance: income - expense + refund, count: count };
}

export function categoryTotals(entries: LedgerEntry[], period: Period, kind: string = 'expense', custom: CustomCategory[] = []): CategoryTotal[] {
  const relevant: LedgerEntry[] = entries.filter((entry: LedgerEntry) => inPeriod(entry, period) && entry.kind === kind);
  const sum: number = relevant.reduce((total: number, entry: LedgerEntry) => total + entry.amountMinor, 0);
  return categoryCatalog(custom).map((category: Category) => {
    const selected: LedgerEntry[] = relevant.filter((entry: LedgerEntry) => entry.categoryId === category.id);
    const amount: number = selected.reduce((total: number, entry: LedgerEntry) => total + entry.amountMinor, 0);
    return { category: category, amount: amount, ratio: sum === 0 ? 0 : amount / sum, count: selected.length };
  }).filter((item: CategoryTotal) => item.amount > 0).sort((a: CategoryTotal, b: CategoryTotal) => b.amount - a.amount);
}

export function trendPoints(entries: LedgerEntry[], period: Period): TrendPoint[] {
  const points: TrendPoint[] = [];
  if (period.mode === 'year') {
    for (let month: number = 1; month <= 12; month++) {
      const prefix: string = period.start.slice(0, 4) + '-' + String(month).padStart(2, '0');
      const selected: LedgerEntry[] = entries.filter((entry: LedgerEntry) => entry.occurredOn.startsWith(prefix));
      const summary: Summary = summarize(selected, period);
      points.push({ date: prefix + '-01', label: month + '月', expense: summary.expense, income: summary.income, refund: summary.refund });
    }
    return points;
  }
  let current: string = period.start;
  let index: number = 0;
  const weekdays: string[] = ['一', '二', '三', '四', '五', '六', '日'];
  while (current <= period.end && index < 32) {
    const dayPeriod: Period = { start: current, end: current, label: current, mode: 'day' };
    const summary: Summary = summarize(entries, dayPeriod);
    points.push({ date: current, label: period.mode === 'week' ? weekdays[index] : String(Number(current.slice(8))), expense: summary.expense, income: summary.income, refund: summary.refund });
    current = shiftDays(current, 1);
    index++;
  }
  return points;
}

export function filterEntries(entries: LedgerEntry[], search: string, kind: string = 'all', categoryId: string = '', custom: CustomCategory[] = []): LedgerEntry[] {
  const query: string = search.trim().toLowerCase();
  return entries.filter((entry: LedgerEntry) => entry.deletedAt === 0 &&
    (kind === 'all' || entry.kind === kind) &&
    (categoryId === '' || entry.categoryId === categoryId) &&
    (query === '' || (entry.title + entry.note + categoryById(entry.categoryId, custom).name + decimalAmount(entry.amountMinor) + entry.occurredOn).toLowerCase().includes(query)))
    .sort((a: LedgerEntry, b: LedgerEntry) => b.occurredOn.localeCompare(a.occurredOn) || b.createdAt - a.createdAt);
}

export function validateEntry(entry: LedgerEntry, custom: CustomCategory[] = []): string {
  if (entry === null || typeof entry !== 'object') return '账目格式不正确';
  if (typeof entry.id !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(entry.id)) return '账目编号不正确';
  if (!KINDS.includes(entry.kind)) return '请选择收支类型';
  if (!Number.isSafeInteger(entry.amountMinor) || entry.amountMinor <= 0 || entry.amountMinor > 999999999999) return '请输入有效金额，最多两位小数';
  if (entry.currency !== 'CNY') return '当前版本支持人民币记账';
  if (typeof entry.title !== 'string' || entry.title.trim().length === 0 || entry.title.length > 80) return '请输入80字以内的账目名称';
  if (!categoryCatalog(custom).some((category: Category) => category.id === entry.categoryId)) return '请选择有效分类';
  if (!categoriesForKind(entry.kind, custom).some((category: Category) => category.id === entry.categoryId)) return '该分类不适用于当前收支类型';
  if (!isDateKey(entry.occurredOn)) return '请选择有效日期';
  if (typeof entry.note !== 'string' || entry.note.length > 500) return '备注最多500字';
  if (typeof entry.timeHint !== 'string' || entry.timeHint.length > 40) return '时间描述格式不正确';
  if (typeof entry.groupId !== 'string' || typeof entry.linkedEntryId !== 'string') return '账目关联格式不正确';
  if (!['voice', 'manual', 'text', 'sample', 'import'].includes(entry.source)) return '账目来源不正确';
  if (!Number.isSafeInteger(entry.createdAt) || entry.createdAt <= 0 || !Number.isSafeInteger(entry.updatedAt) || entry.updatedAt <= 0 || !Number.isSafeInteger(entry.deletedAt) || entry.deletedAt < 0) return '账目时间格式不正确';
  return '';
}

function csvCell(value: string): string {
  const text: string = /^[=+@\-\t\r]/.test(value) ? "'" + value : value;
  return '"' + text.replace(/"/g, '""') + '"';
}

export function exportCsv(entries: LedgerEntry[], custom: CustomCategory[] = []): string {
  const rows: string[] = ['日期,类型,分类,名称,金额（元）,币种,备注'];
  filterEntries(entries, '').forEach((entry: LedgerEntry) => {
    rows.push([entry.occurredOn, kindName(entry.kind), categoryById(entry.categoryId, custom).name, entry.title, decimalAmount(entry.amountMinor), entry.currency, entry.note].map(csvCell).join(','));
  });
  return '\uFEFF' + rows.join('\r\n');
}

export function validateMonthlyBudgets(budgets: MonthlyBudget[]): string {
  if (!Array.isArray(budgets) || budgets.length > 3600) return '月预算数量不正确';
  const months: string[] = [];
  for (const budget of budgets) {
    if (!budget || typeof budget.month !== 'string' || !/^\d{4}-\d{2}$/.test(budget.month) || !isDateKey(budget.month + '-01')) return '预算月份不正确';
    if (!Number.isSafeInteger(budget.amountMinor) || budget.amountMinor < 0 || budget.amountMinor > 999999999999) return '预算金额不正确';
    if (months.includes(budget.month)) return '预算含重复月份';
    months.push(budget.month);
  }
  return '';
}

export function mergeMonthlyBudgets(existing: MonthlyBudget[], incoming: MonthlyBudget[]): MonthlyBudget[] {
  const error: string = validateMonthlyBudgets(existing) || validateMonthlyBudgets(incoming);
  if (error) throw new Error(error);
  // Explicit settings already on this phone win; importing a backup never replaces a chosen limit.
  const merged: MonthlyBudget[] = existing.concat(incoming.filter((item: MonthlyBudget) =>
    !existing.some((saved: MonthlyBudget) => saved.month === item.month))).sort((a: MonthlyBudget, b: MonthlyBudget) => a.month.localeCompare(b.month));
  const combinedError: string = validateMonthlyBudgets(merged);
  if (combinedError) throw new Error(combinedError);
  return merged;
}

export function exportBackup(entries: LedgerEntry[], exportedAt: string = new Date().toISOString(), custom: CustomCategory[] = [], budgets: MonthlyBudget[] = []): string {
  const backup: BackupDocument = { format: 'yiyu-ledger', version: 3, exportedAt: exportedAt,
    entries: entries.filter((entry: LedgerEntry) => entry.deletedAt === 0), categories: custom, monthlyBudgets: budgets };
  return JSON.stringify(backup, null, 2);
}

export function parseBackup(content: string): LedgerEntry[] {
  return parseBackupDocument(content).entries;
}

export function parseBackupDocument(content: string): BackupDocument {
  if (content.length > 5 * 1024 * 1024) throw new Error('备份文件过大');
  const document: BackupDocument = JSON.parse(content) as BackupDocument;
  if (document === null || document.format !== 'yiyu-ledger' || ![1, 2, 3].includes(document.version) || !Array.isArray(document.entries) || document.entries.length > 20000) throw new Error('不支持此备份格式');
  const custom: CustomCategory[] = document.version === 1 ? [] : document.categories;
  const categoryError: string = validateCategoryCatalog(custom);
  if (categoryError) throw new Error(categoryError);
  const budgets: MonthlyBudget[] = document.version < 3 ? [] : document.monthlyBudgets;
  const budgetError: string = validateMonthlyBudgets(budgets);
  if (budgetError) throw new Error(budgetError);
  const ids: string[] = [];
  document.entries.forEach((entry: LedgerEntry) => {
    const error: string = validateEntry(entry, custom);
    if (error !== '') throw new Error(error);
    if (ids.includes(entry.id)) throw new Error('备份含重复账目编号');
    ids.push(entry.id);
  });
  return { format: document.format, version: document.version, exportedAt: document.exportedAt,
    entries: document.entries.filter((entry: LedgerEntry) => entry.deletedAt === 0), categories: custom, monthlyBudgets: budgets };
}

export function seedEntries(reference: string = dateKey()): LedgerEntry[] {
  const month: string = reference.slice(0, 7);
  const today: number = Number(reference.slice(8));
  const descriptions: string[] = ['辣椒炒肉盖饭', '打车回家', '冰美式', '超市日用品', '电影票', '十月房租', '帆布包', '地铁', '一本书', '水果', '晚餐', '工资到账'];
  const amounts: number[] = [1500, 2200, 1800, 7650, 4500, 80000, 12800, 800, 5200, 6000, 6200, 800000];
  const categories: string[] = ['food', 'transport', 'food', 'daily', 'entertainment', 'housing', 'shopping', 'transport', 'education', 'food', 'food', 'salary'];
  const days: number[] = [today, today, today, today - 1, today - 1, 1, today - 2, today - 2, today - 3, today - 3, today - 4, 5];
  const now: number = Date.now();
  return descriptions.map((title: string, index: number) => {
    return {
      id: 'sample_' + index,
      kind: index === 11 ? 'income' : 'expense',
      amountMinor: amounts[index],
      currency: 'CNY',
      categoryId: categories[index],
      title: title === '十月房租' ? Number(month.slice(5)) + '月房租' : title,
      occurredOn: month + '-' + String(Math.max(1, Math.min(today, days[index]))).padStart(2, '0'),
      timeHint: index === 0 ? '中午' : '',
      note: '',
      source: 'sample',
      groupId: '',
      linkedEntryId: '',
      createdAt: now - index * 1000,
      updatedAt: now,
      deletedAt: 0
    };
  });
}
