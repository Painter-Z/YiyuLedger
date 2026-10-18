// Pure domain code, shared by the Harmony client and the interactive design preview.
// Amounts are positive integer CNY cents; direction is represented by `kind`.
                           
             
               
               
                
                     
 

                                 
             
               
               
 

                                
                
                      
 

                              
             
               
                      
                   
                     
                
                     
                   
               
                 
                  
                        
                    
                    
                    
 

                         
                
              
                
               
 

                          
                  
                 
                 
                     
                  
                
 

                                
                     
                 
                
                
 

                             
               
                
                  
                 
                 
 

                                 
                 
                  
                     
                         
                               
                                  
 

export const CATEGORIES             = [
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

export const KINDS           = ['expense', 'income', 'refund', 'transfer', 'debt'];

export function kindName(kind        )         {
  if (kind === 'income') return '收入';
  if (kind === 'refund') return '退款';
  if (kind === 'transfer') return '转账';
  if (kind === 'debt') return '往来';
  return '支出';
}

export function categoryScope(kind        )         { return kind === 'refund' ? 'expense' : kind; }

export function categoryCatalog(custom                   = [])             {
  return CATEGORIES.concat(custom.map((item                )           => {
    return { id: item.id, name: item.name, icon: 'dots', color: '#63806A', background: '#E5EEE4' };
  }));
}

export function categoryById(id        , custom                   = [])           {
  const result                       = categoryCatalog(custom).find((item          ) => item.id === id);
  return result === undefined ? CATEGORIES[CATEGORIES.length - 1] : result;
}

export function categoriesForKind(kind        , custom                   = [])             {
  const extra             = categoryCatalog(custom.filter((item                ) => item.kind === categoryScope(kind))).slice(CATEGORIES.length);
  if (kind === 'income') {
    return CATEGORIES.filter((item          ) => ['salary', 'bonus', 'freelance', 'investment', 'social', 'other'].includes(item.id)).concat(extra);
  }
  if (kind === 'transfer') return CATEGORIES.filter((item          ) => item.id === 'transfer').concat(extra);
  if (kind === 'debt') return CATEGORIES.filter((item          ) => item.id === 'debt').concat(extra);
  return CATEGORIES.filter((item          ) => !['salary', 'bonus', 'freelance', 'investment', 'transfer', 'debt'].includes(item.id)).concat(extra);
}

export function validateCategoryCatalog(custom                  )         {
  if (!Array.isArray(custom) || custom.length > 100) return '自定义分类最多100个';
  const ids           = [];
  const names           = [];
  for (const item of custom) {
    if (!item || typeof item.id !== 'string' || !/^custom_[a-zA-Z0-9_-]{1,64}$/.test(item.id)) return '自定义分类编号不正确';
    if (!['expense', 'income', 'transfer', 'debt'].includes(item.kind)) return '自定义分类类型不正确';
    if (typeof item.name !== 'string' || !item.name.trim() || item.name !== item.name.trim() || item.name.length > 12 || /[\x00-\x1f\x7f]/.test(item.name)) return '分类名称请填写1至12个字';
    if (['更多', '收起', '自定义'].includes(item.name)) return '请使用具体的分类名称';
    const key         = item.kind + '_' + item.name.toLowerCase();
    if (ids.includes(item.id) || names.includes(key) || categoriesForKind(item.kind).some((base          ) => base.name === item.name)) return '该类型下已有同名分类';
    ids.push(item.id); names.push(key);
  }
  return '';
}

// Stable identifiers must keep their meaning when a backup is merged into an existing book.
export function mergeCategoryCatalog(existing                  , incoming                  )                   {
  const combined                   = existing.slice();
  for (const item of incoming) {
    const found                             = existing.find((current                ) => current.id === item.id);
    if (found && (found.name !== item.name || found.kind !== item.kind)) throw new Error('备份分类编号冲突，原账本已保留');
    if (!found) combined.push(item);
  }
  const error         = validateCategoryCatalog(combined);
  if (error) throw new Error(error);
  return combined;
}

export function parseMinor(value        )         {
  const text         = value.trim();
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(text)) return -1;
  const parts           = text.split('.');
  const amount         = Number(parts[0]) * 100 + Number(((parts[1] || '') + '00').slice(0, 2));
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > 999999999999) return -1;
  return amount;
}

export function decimalAmount(minor        )         {
  if (!Number.isSafeInteger(minor)) return '0.00';
  const sign         = minor < 0 ? '-' : '';
  const amount         = Math.abs(minor);
  return sign + String(Math.floor(amount / 100)) + '.' + String(amount % 100).padStart(2, '0');
}

export function formatMoney(minor        )         {
  const parts           = decimalAmount(minor).split('.');
  return parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + parts[1];
}

export function dateKey(date       = new Date())         {
  return String(date.getFullYear()).padStart(4, '0') + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
}

function utcKey(date      )         {
  return String(date.getUTCFullYear()).padStart(4, '0') + '-' + String(date.getUTCMonth() + 1).padStart(2, '0') + '-' + String(date.getUTCDate()).padStart(2, '0');
}

export function isDateKey(value        )          {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed       = new Date(value + 'T00:00:00Z');
  return !Number.isNaN(parsed.getTime()) && utcKey(parsed) === value && value >= '1900-01-01' && value <= '2199-12-31';
}

export function shiftDays(value        , delta        )         {
  const parsed       = new Date(value + 'T00:00:00Z');
  parsed.setUTCDate(parsed.getUTCDate() + delta);
  return utcKey(parsed);
}

export function periodFor(mode        , reference        , offset         = 0)         {
  const date       = new Date(reference + 'T00:00:00Z');
  if (mode === 'week') {
    const weekday         = (date.getUTCDay() + 6) % 7;
    const start         = shiftDays(reference, -weekday + offset * 7);
    const end         = shiftDays(start, 6);
    return { start: start, end: end, label: Number(start.slice(5, 7)) + '.' + Number(start.slice(8)) + ' — ' + Number(end.slice(5, 7)) + '.' + Number(end.slice(8)), mode: mode };
  }
  if (mode === 'year') {
    const year         = date.getUTCFullYear() + offset;
    return { start: year + '-01-01', end: year + '-12-31', label: year + '年', mode: mode };
  }
  const first       = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + offset, 1));
  const last       = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0));
  return { start: utcKey(first), end: utcKey(last), label: first.getUTCFullYear() + '年' + (first.getUTCMonth() + 1) + '月', mode: 'month' };
}

export function inPeriod(entry             , period        )          {
  return entry.deletedAt === 0 && entry.currency === 'CNY' && entry.occurredOn >= period.start && entry.occurredOn <= period.end;
}

export function summarize(entries               , period        )          {
  let expense         = 0;
  let income         = 0;
  let refund         = 0;
  let count         = 0;
  entries.forEach((entry             ) => {
    if (!inPeriod(entry, period)) return;
    count++;
    if (entry.kind === 'expense') expense += entry.amountMinor;
    if (entry.kind === 'income') income += entry.amountMinor;
    if (entry.kind === 'refund') refund += entry.amountMinor;
  });
  return { expense: expense, income: income, refund: refund, netExpense: expense - refund, balance: income - expense + refund, count: count };
}

export function categoryTotals(entries               , period        , kind         = 'expense', custom                   = [])                  {
  const relevant                = entries.filter((entry             ) => inPeriod(entry, period) && entry.kind === kind);
  const sum         = relevant.reduce((total        , entry             ) => total + entry.amountMinor, 0);
  return categoryCatalog(custom).map((category          ) => {
    const selected                = relevant.filter((entry             ) => entry.categoryId === category.id);
    const amount         = selected.reduce((total        , entry             ) => total + entry.amountMinor, 0);
    return { category: category, amount: amount, ratio: sum === 0 ? 0 : amount / sum, count: selected.length };
  }).filter((item               ) => item.amount > 0).sort((a               , b               ) => b.amount - a.amount);
}

export function trendPoints(entries               , period        )               {
  const points               = [];
  if (period.mode === 'year') {
    for (let month         = 1; month <= 12; month++) {
      const prefix         = period.start.slice(0, 4) + '-' + String(month).padStart(2, '0');
      const selected                = entries.filter((entry             ) => entry.occurredOn.startsWith(prefix));
      const summary          = summarize(selected, period);
      points.push({ date: prefix + '-01', label: month + '月', expense: summary.expense, income: summary.income, refund: summary.refund });
    }
    return points;
  }
  let current         = period.start;
  let index         = 0;
  const weekdays           = ['一', '二', '三', '四', '五', '六', '日'];
  while (current <= period.end && index < 32) {
    const dayPeriod         = { start: current, end: current, label: current, mode: 'day' };
    const summary          = summarize(entries, dayPeriod);
    points.push({ date: current, label: period.mode === 'week' ? weekdays[index] : String(Number(current.slice(8))), expense: summary.expense, income: summary.income, refund: summary.refund });
    current = shiftDays(current, 1);
    index++;
  }
  return points;
}

export function filterEntries(entries               , search        , kind         = 'all', categoryId         = '', custom                   = [])                {
  const query         = search.trim().toLowerCase();
  return entries.filter((entry             ) => entry.deletedAt === 0 &&
    (kind === 'all' || entry.kind === kind) &&
    (categoryId === '' || entry.categoryId === categoryId) &&
    (query === '' || (entry.title + entry.note + categoryById(entry.categoryId, custom).name + decimalAmount(entry.amountMinor) + entry.occurredOn).toLowerCase().includes(query)))
    .sort((a             , b             ) => b.occurredOn.localeCompare(a.occurredOn) || b.createdAt - a.createdAt);
}

export function validateEntry(entry             , custom                   = [])         {
  if (entry === null || typeof entry !== 'object') return '账目格式不正确';
  if (typeof entry.id !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(entry.id)) return '账目编号不正确';
  if (!KINDS.includes(entry.kind)) return '请选择收支类型';
  if (!Number.isSafeInteger(entry.amountMinor) || entry.amountMinor <= 0 || entry.amountMinor > 999999999999) return '请输入有效金额，最多两位小数';
  if (entry.currency !== 'CNY') return '当前版本支持人民币记账';
  if (typeof entry.title !== 'string' || entry.title.trim().length === 0 || entry.title.length > 80) return '请输入80字以内的账目名称';
  if (!categoryCatalog(custom).some((category          ) => category.id === entry.categoryId)) return '请选择有效分类';
  if (!categoriesForKind(entry.kind, custom).some((category          ) => category.id === entry.categoryId)) return '该分类不适用于当前收支类型';
  if (!isDateKey(entry.occurredOn)) return '请选择有效日期';
  if (typeof entry.note !== 'string' || entry.note.length > 500) return '备注最多500字';
  if (typeof entry.timeHint !== 'string' || entry.timeHint.length > 40) return '时间描述格式不正确';
  if (typeof entry.groupId !== 'string' || typeof entry.linkedEntryId !== 'string') return '账目关联格式不正确';
  if (!['voice', 'manual', 'text', 'sample', 'import'].includes(entry.source)) return '账目来源不正确';
  if (!Number.isSafeInteger(entry.createdAt) || entry.createdAt <= 0 || !Number.isSafeInteger(entry.updatedAt) || entry.updatedAt <= 0 || !Number.isSafeInteger(entry.deletedAt) || entry.deletedAt < 0) return '账目时间格式不正确';
  return '';
}

function csvCell(value        )         {
  const text         = /^[=+@\-\t\r]/.test(value) ? "'" + value : value;
  return '"' + text.replace(/"/g, '""') + '"';
}

export function exportCsv(entries               , custom                   = [])         {
  const rows           = ['日期,类型,分类,名称,金额（元）,币种,备注'];
  filterEntries(entries, '').forEach((entry             ) => {
    rows.push([entry.occurredOn, kindName(entry.kind), categoryById(entry.categoryId, custom).name, entry.title, decimalAmount(entry.amountMinor), entry.currency, entry.note].map(csvCell).join(','));
  });
  return '\uFEFF' + rows.join('\r\n');
}

export function validateMonthlyBudgets(budgets                 )         {
  if (!Array.isArray(budgets) || budgets.length > 3600) return '月预算数量不正确';
  const months           = [];
  for (const budget of budgets) {
    if (!budget || typeof budget.month !== 'string' || !/^\d{4}-\d{2}$/.test(budget.month) || !isDateKey(budget.month + '-01')) return '预算月份不正确';
    if (!Number.isSafeInteger(budget.amountMinor) || budget.amountMinor < 0 || budget.amountMinor > 999999999999) return '预算金额不正确';
    if (months.includes(budget.month)) return '预算含重复月份';
    months.push(budget.month);
  }
  return '';
}

export function mergeMonthlyBudgets(existing                 , incoming                 )                  {
  const error         = validateMonthlyBudgets(existing) || validateMonthlyBudgets(incoming);
  if (error) throw new Error(error);
  // Explicit settings already on this phone win; importing a backup never replaces a chosen limit.
  const merged                  = existing.concat(incoming.filter((item               ) =>
    !existing.some((saved               ) => saved.month === item.month))).sort((a               , b               ) => a.month.localeCompare(b.month));
  const combinedError         = validateMonthlyBudgets(merged);
  if (combinedError) throw new Error(combinedError);
  return merged;
}

export function exportBackup(entries               , exportedAt         = new Date().toISOString(), custom                   = [], budgets                  = [])         {
  const backup                 = { format: 'yiyu-ledger', version: 3, exportedAt: exportedAt,
    entries: entries.filter((entry             ) => entry.deletedAt === 0), categories: custom, monthlyBudgets: budgets };
  return JSON.stringify(backup, null, 2);
}

export function parseBackup(content        )                {
  return parseBackupDocument(content).entries;
}

export function parseBackupDocument(content        )                 {
  if (content.length > 5 * 1024 * 1024) throw new Error('备份文件过大');
  const document                 = JSON.parse(content)                  ;
  if (document === null || document.format !== 'yiyu-ledger' || ![1, 2, 3].includes(document.version) || !Array.isArray(document.entries) || document.entries.length > 20000) throw new Error('不支持此备份格式');
  const custom                   = document.version === 1 ? [] : document.categories;
  const categoryError         = validateCategoryCatalog(custom);
  if (categoryError) throw new Error(categoryError);
  const budgets                  = document.version < 3 ? [] : document.monthlyBudgets;
  const budgetError         = validateMonthlyBudgets(budgets);
  if (budgetError) throw new Error(budgetError);
  const ids           = [];
  document.entries.forEach((entry             ) => {
    const error         = validateEntry(entry, custom);
    if (error !== '') throw new Error(error);
    if (ids.includes(entry.id)) throw new Error('备份含重复账目编号');
    ids.push(entry.id);
  });
  return { format: document.format, version: document.version, exportedAt: document.exportedAt,
    entries: document.entries.filter((entry             ) => entry.deletedAt === 0), categories: custom, monthlyBudgets: budgets };
}

export function seedEntries(reference         = dateKey())                {
  const month         = reference.slice(0, 7);
  const today         = Number(reference.slice(8));
  const descriptions           = ['辣椒炒肉盖饭', '打车回家', '冰美式', '超市日用品', '电影票', '十月房租', '帆布包', '地铁', '一本书', '水果', '晚餐', '工资到账'];
  const amounts           = [1500, 2200, 1800, 7650, 4500, 80000, 12800, 800, 5200, 6000, 6200, 800000];
  const categories           = ['food', 'transport', 'food', 'daily', 'entertainment', 'housing', 'shopping', 'transport', 'education', 'food', 'food', 'salary'];
  const days           = [today, today, today, today - 1, today - 1, 1, today - 2, today - 2, today - 3, today - 3, today - 4, 5];
  const now         = Date.now();
  return descriptions.map((title        , index        ) => {
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
