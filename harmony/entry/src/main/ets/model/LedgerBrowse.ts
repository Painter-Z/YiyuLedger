import type { CustomCategory, LedgerEntry } from './Ledger';
import { filterEntries, isDateKey, periodFor, shiftDays } from './Ledger';

export interface LedgerBrowseOptions {
  today: string;
  search: string;
  kind: string;
  categoryId: string;
  dateFilter: string;
  rangeStart: string;
  rangeEnd: string;
}

export interface LedgerDateScope {
  mode: string;
  start: string;
  end: string;
  label: string;
}

export interface LedgerBrowseResult {
  scope: LedgerDateScope;
  entries: LedgerEntry[];
  expenseByDay: Map<string, number>;
  emptyDay: string;
}

export interface LedgerDay {
  date: string;
  expenseMinor: number;
  entries: LedgerEntry[];
  revision: string;
}

export function ledgerDateScope(options: LedgerBrowseOptions): LedgerDateScope {
  if (!isDateKey(options.today)) throw new Error('当前日期不正确');
  if (options.dateFilter) {
    if (isDateKey(options.dateFilter)) {
      return { mode: 'day', start: options.dateFilter, end: options.dateFilter, label: '查看：' + options.dateFilter };
    }
    if (/^\d{4}-\d{2}$/.test(options.dateFilter) && isDateKey(options.dateFilter + '-01')) {
      const month = periodFor('month', options.dateFilter + '-01');
      return { mode: 'month', start: month.start, end: month.end, label: '查看：' + month.label };
    }
    throw new Error('请选择有效日期');
  }
  if (options.rangeStart || options.rangeEnd) {
    if (!isDateKey(options.rangeStart) || !isDateKey(options.rangeEnd) || options.rangeStart > options.rangeEnd)
      throw new Error('日期范围不正确');
    return { mode: 'period', start: options.rangeStart, end: options.rangeEnd,
      label: '范围：' + options.rangeStart + ' 至 ' + options.rangeEnd };
  }
  if (options.search.trim()) return { mode: 'all', start: '', end: '', label: '全部日期 · 搜索结果' };
  const start = shiftDays(options.today, -2);
  return { mode: 'recent', start: start, end: options.today,
    label: '最近三天 · ' + start.slice(5).replace('-', '.') + '—' + options.today.slice(5).replace('-', '.') };
}

/** Compute scope and complete daily expenses once, independently of keyword/type/category filters. */
export function browseLedger(entries: LedgerEntry[], options: LedgerBrowseOptions, custom: CustomCategory[] = []): LedgerBrowseResult {
  const scope = ledgerDateScope(options);
  const eligible: LedgerEntry[] = [];
  const expenseByDay = new Map<string, number>();
  for (const entry of entries) {
    if (entry.deletedAt !== 0 || entry.currency !== 'CNY' ||
      (scope.start && (entry.occurredOn < scope.start || entry.occurredOn > scope.end))) continue;
    eligible.push(entry);
    if (entry.kind === 'expense') expenseByDay.set(entry.occurredOn, (expenseByDay.get(entry.occurredOn) || 0) + entry.amountMinor);
  }
  return { scope: scope, entries: filterEntries(eligible, options.search, options.kind, options.categoryId, custom),
    expenseByDay: expenseByDay, emptyDay: scope.mode === 'day' ? scope.start : scope.mode === 'recent' ? options.today : '' };
}

/** Bound the rendered list, while each day's expense remains the full day's amount. */
export function ledgerDayPage(result: LedgerBrowseResult, limit: number = 80): LedgerDay[] {
  const groups = new Map<string, LedgerEntry[]>();
  for (const entry of result.entries.slice(0, Math.max(1, Math.floor(limit)))) {
    const day = groups.get(entry.occurredOn);
    if (day) day.push(entry);
    else groups.set(entry.occurredOn, [entry]);
  }
  if (result.emptyDay && !groups.has(result.emptyDay)) groups.set(result.emptyDay, []);
  const days: LedgerDay[] = [];
  groups.forEach((entries: LedgerEntry[], date: string) => {
    const expense = result.expenseByDay.get(date) || 0;
    days.push({ date: date, expenseMinor: expense, entries: entries,
      revision: date + '_' + expense + '_' + entries.map((entry: LedgerEntry) => entry.id + '_' + entry.updatedAt).join('_') });
  });
  return days.sort((a: LedgerDay, b: LedgerDay) => b.date.localeCompare(a.date));
}

export function ledgerDayLabel(day: string, today: string): string {
  if (day === today) return '今天 · ' + day.slice(5);
  if (day === shiftDays(today, -1)) return '昨天 · ' + day.slice(5);
  if (day === shiftDays(today, -2)) return '前天 · ' + day.slice(5);
  return day;
}
