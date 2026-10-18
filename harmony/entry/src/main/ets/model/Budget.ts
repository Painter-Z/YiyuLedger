import type { LedgerEntry, MonthlyBudget, Period } from './Ledger';
import { periodFor, summarize, validateMonthlyBudgets } from './Ledger';

export interface BudgetProgress {
  month: string;
  limit: number;
  spent: number;
  remaining: number;
  overspent: number;
  usedRatio: number;
  baseRatio: number;
  overRatio: number;
  state: string;
}

export function budgetLimit(budgets: MonthlyBudget[], month: string): number {
  const error: string = validateMonthlyBudgets(budgets);
  if (error) throw new Error(error);
  const selected: MonthlyBudget | undefined = budgets.filter((item: MonthlyBudget) => item.month <= month)
    .sort((a: MonthlyBudget, b: MonthlyBudget) => b.month.localeCompare(a.month))[0];
  return selected ? selected.amountMinor : 0;
}

export function monthlyBudgetProgress(entries: LedgerEntry[], budgets: MonthlyBudget[], month: string): BudgetProgress {
  if (validateMonthlyBudgets([{ month: month, amountMinor: 0 }])) throw new Error('预算月份不正确');
  const period: Period = periodFor('month', month + '-01');
  const spent: number = Math.max(0, summarize(entries, period).netExpense);
  const limit: number = budgetLimit(budgets, month);
  const usedRatio: number = limit > 0 ? spent / limit : 0;
  return { month: month, limit: limit, spent: spent, remaining: Math.max(0, limit - spent),
    overspent: limit > 0 ? Math.max(0, spent - limit) : 0, usedRatio: usedRatio,
    baseRatio: Math.min(1, usedRatio), overRatio: Math.max(0, Math.min(1, usedRatio - 1)),
    state: limit === 0 ? 'unset' : spent < limit ? 'available' : spent === limit ? 'reached' : 'over' };
}

// Month view follows the selected month. Weekly/yearly statistics retain a clearly dated current-month budget.
export function budgetMonthFor(mode: string, period: Period, today: string): string {
  return (mode === 'month' ? period.start : today).slice(0, 7);
}
