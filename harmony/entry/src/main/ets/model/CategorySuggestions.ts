import type { Category, CustomCategory, LedgerEntry } from './Ledger';
import { categoriesForKind, categoryScope } from './Ledger';

function keywords(id: string): string[] {
  if (id === 'food') return ['饭', '餐', '面', '粉', '菜', '奶茶', '咖啡', '水果', '外卖'];
  if (id === 'transport') return ['打车', '地铁', '公交', '加油', '停车', '出租车', '火车'];
  if (id === 'shopping') return ['衣服', '鞋', '包', '买', '网购', '淘宝'];
  if (id === 'daily') return ['超市', '日用品', '纸巾', '洗发', '牙膏', '生活用品'];
  if (id === 'housing') return ['房租', '水费', '电费', '物业', '燃气', '宽带'];
  if (id === 'entertainment') return ['电影', '游戏', '唱歌', '会员', '演出'];
  if (id === 'health') return ['医院', '看病', '药', '体检', '牙医'];
  if (id === 'education') return ['书', '学费', '课程', '培训', '考试'];
  if (id === 'travel') return ['旅游', '酒店', '机票', '景点', '旅行'];
  if (id === 'social') return ['红包', '礼物', '份子', '随礼', '请客'];
  if (id === 'salary') return ['工资', '薪水', '发薪'];
  if (id === 'bonus') return ['奖金', '年终奖'];
  if (id === 'freelance') return ['兼职', '稿费', '接单'];
  if (id === 'investment') return ['利息', '理财', '分红'];
  return [];
}

// These are convenience suggestions, not calibrated model probabilities. Never replace the selected category.
export function suggestedCategories(kind: string, selected: string, description: string, title: string,
  entries: LedgerEntry[], custom: CustomCategory[] = [], limit: number = 3): Category[] {
  const available: Category[] = categoriesForKind(kind, custom);
  const text: string = (title + ' ' + description).toLowerCase();
  const current: Category = available.find((item: Category) => item.id === selected) || available[0];
  const score = (category: Category): number => {
    let points: number = text.includes(category.name.toLowerCase()) ? 30 : 0;
    keywords(category.id).forEach((word: string) => { if (text.includes(word)) points += 6; });
    const history: LedgerEntry[] = entries.filter((entry: LedgerEntry) => entry.deletedAt === 0 &&
      categoryScope(entry.kind) === categoryScope(kind) && entry.categoryId === category.id);
    points += Math.min(history.length, 6);
    if (title.trim() && history.some((entry: LedgerEntry) => entry.title.trim().toLowerCase() === title.trim().toLowerCase())) points += 40;
    return points;
  };
  const others: Category[] = available.filter((item: Category) => item.id !== current.id);
  others.sort((a: Category, b: Category) => score(b) - score(a) || available.indexOf(a) - available.indexOf(b));
  return [current].concat(others).slice(0, Math.max(1, limit));
}
