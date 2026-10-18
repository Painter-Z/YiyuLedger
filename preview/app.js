import { CATEGORIES, categoryById, categoriesForKind, dateKey, periodFor, summarize, categoryTotals, trendPoints, filterEntries, formatMoney, decimalAmount, parseMinor, validateEntry, seedEntries, exportCsv, exportBackup, parseBackupDocument, kindName } from './ledger-core.js';

const icons = {
  mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M6 10v2a6 6 0 0 0 12 0v-2M12 18v3m-3 0h6"/>',
  home: '<path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 21v-8h6v8"/>',
  ledger: '<rect x="5" y="3" width="15" height="18" rx="3"/><path d="M5 8H3m2 5H3m2 5H3m7-11h5m-5 5h5m-5 5h3"/>',
  chart: '<path d="M4 4v16h17M8 15v-5m5 5V6m5 9v-7"/>',
  settings: '<path d="m9 3-.5 2-2 1L4.5 5.5 3 8l1.5 1.5v3L3 14l1.5 2.5L6.5 16l2 1L9 19h3l.5-2 2-1 2 .5L18 14l-1.5-1.5v-3L18 8l-1.5-2.5-2 .5-2-1L12 3z" transform="translate(1.5 1)"/><circle cx="12" cy="12" r="3"/>',
  food: '<path d="M5 3v6a2 2 0 0 0 4 0V3M7 3v18M17 3c-3 4-3 8 0 9h2V3h-2Zm0 9v9"/>',
  car: '<path d="m5 6-2 7v5h18v-5l-2-7H5zm-1 7h16M6 18v3m12-3v3M6 15h1m10 0h1"/>',
  bag: '<path d="M5 7h14l2 14H3L5 7Z"/><path d="M8 8V6a4 4 0 0 1 8 0v2"/>',
  basket: '<path d="m4 10 2 10h12l2-10H4Zm4-1 4-6 4 6m-7 5v3m6-3v3"/>',
  play: '<rect x="3" y="5" width="18" height="15" rx="3"/><path d="m10 9 5 3.5-5 3.5zM7 5V3m10 2V3"/>',
  heart: '<path d="M20 5c-2-3-6-2-8 1-2-3-6-4-8-1-4 6 8 14 8 14S24 11 20 5Z"/>',
  book: '<path d="M12 6C8 3 5 3 3 4v15c3-1 6-1 9 2 3-3 6-3 9-2V4c-2-1-5-1-9 2Zm0 0v15"/>',
  plane: '<path d="m3 13 7-3 2-7 2 7 7 3-7 2v5l-2-1-2 1v-5z"/>',
  gift: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M5 12v9h14v-9M12 8v13M12 8C3 8 6 0 9 3l3 5Zm0 0c9 0 6-8 3-5l-3 5Z"/>',
  wallet: '<rect x="3" y="6" width="18" height="14" rx="3"/><path d="M4 6V4h14v2m3 5h-6v5h6m-3-2.5h.01"/>',
  briefcase: '<rect x="3" y="7" width="18" height="14" rx="2"/><path d="M8 7V3h8v4M3 13h18m-9-2v4"/>',
  trend: '<path d="m3 17 6-6 4 3 8-10m-6 0h6v6"/>',
  swap: '<path d="M3 8h17l-4-4m5 12H4l4 4"/>',
  dots: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  edit: '<path d="m15 3 6 6-12 12H3v-6L15 3Zm-2 2 6 6M3 21l6-1-5-5"/>',
  text: '<path d="M21 15a3 3 0 0 1-3 3H9l-6 3V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v9ZM7 8h10M7 12h7"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  check: '<path d="m5 12 4 4L20 5"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
  income: '<path d="m7 7 10 10m0-8v8H9"/>',
  export: '<path d="M12 3v12m-4-4 4 4 4-4M4 15v6h16v-6"/>',
  import: '<path d="M12 16V4m-4 4 4-4 4 4M4 15v6h16v-6"/>',
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9S4 17 4 12V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4m10-4v4M3 10h18m-13 4h1m4 0h1m-6 3h1m4 0h1"/>',
  back: '<path d="m15 5-7 7 7 7"/>'
};
export function icon(name, cls = '') { return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.dots}</svg>`; }
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
const storeKey = 'yiyu-design-preview-v1';
let saved = null;
try { saved = JSON.parse(localStorage.getItem(storeKey)); } catch { /* A corrupt preview cache never affects the native ledger. */ }
const state = { tab: 'home', entries: Array.isArray(saved) ? saved.filter(entry => entry.deletedAt === 0) : seedEntries(), search: '', kind: 'all', category: '', drillPeriod: null, mode: 'month', offset: 0, rankingKind: 'expense', modal: null, editor: null, queue: [], transcript: '', error: '', holding: false, holdStart: 0, holdY: 0, cancelled: false };
try { localStorage.setItem(storeKey, JSON.stringify(state.entries)); } catch { /* Storage may be disabled in the preview browser. */ }
const app = document.getElementById('app');
let toastTimer;
function persist() { localStorage.setItem(storeKey, JSON.stringify(state.entries)); }
function toast(message) {
  clearTimeout(toastTimer);
  const el = document.getElementById('toast');
  el.textContent = message;
  const hide = () => { el.classList.remove('visible'); el.setAttribute('aria-hidden', 'true'); };
  el.setAttribute('aria-hidden', 'false');
  el.classList.add('visible');
  toastTimer = setTimeout(hide, 2800);
}
function currentPeriod() { return periodFor(state.mode, dateKey(), state.offset); }
function isSample() { return state.entries.some(entry => entry.source === 'sample'); }
function newEntry() { const now = Date.now(); return { id: crypto.randomUUID(), kind: 'expense', amountMinor: 0, currency: 'CNY', categoryId: 'food', title: '', occurredOn: dateKey(), timeHint: '', note: '', source: 'manual', groupId: '', linkedEntryId: '', createdAt: now, updatedAt: now, deletedAt: 0 }; }
function openEditor(entry = null, transcript = '') {
  state.editor = { ...(entry || newEntry()), amount: entry ? decimalAmount(entry.amountMinor) : '', existing: !!entry && state.entries.some(item => item.id === entry.id) };
  state.transcript = transcript;
  state.modal = 'editor'; state.error = ''; render();
}
function topbar() {
  if (state.tab === 'home') return `<header class="topbar"><div class="identity"><img class="brand-icon" src="./assets/app_icon.svg" alt=""><div><div class="brand-title">一语账</div><div class="top-caption">把每一笔，轻轻记下</div></div></div><button class="icon-button" data-action="tab" data-value="settings" aria-label="设置">${icon('settings')}</button></header>`;
  if (state.tab === 'settings') return `<header class="topbar"><button class="icon-button" data-action="tab" data-value="home" aria-label="返回">${icon('back')}</button><span style="font-size:14px">设置</span><span style="width:36px"></span></header>`;
  return `<header class="topbar"><div><h1 class="page-title">${state.tab === 'ledger' ? '每一笔生活' : '看见收支'}</h1><div class="page-subtitle">${state.tab === 'ledger' ? '认真记录，也轻松回看' : '让数字，慢慢说清楚'}</div></div><button class="icon-button" data-action="manual" aria-label="记一笔">${icon('plus')}</button></header>`;
}
function navigation() {
  return `<nav class="nav" aria-label="主导航">${[['home','mic','记账'],['ledger','ledger','账单'],['stats','chart','统计']].map(([tab, symbol, label]) => `<button class="${state.tab === tab ? 'active' : ''}" data-action="tab" data-value="${tab}" ${state.tab === tab ? 'aria-current="page"' : ''}>${icon(symbol)}<span>${label}</span></button>`).join('')}</nav>`;
}
function transaction(entry) {
  const c = categoryById(entry.categoryId);
  const sign = entry.kind === 'expense' ? '−' : ['income','refund'].includes(entry.kind) ? '+' : '';
  const tone = ['income','refund'].includes(entry.kind) ? 'income' : ['transfer','debt'].includes(entry.kind) ? 'neutral' : '';
  return `<button class="transaction-row" data-action="edit" data-value="${esc(entry.id)}"><span class="category-icon" style="background:${c.background};color:${c.color}">${icon(c.icon)}</span><span class="transaction-copy"><span class="transaction-title">${esc(entry.title)}</span><span class="transaction-meta">${esc(c.name)}<i class="dot"></i>${esc(entry.timeHint || (entry.kind === 'expense' ? '支出' : kindName(entry.kind)))}${entry.source === 'voice' ? `<span class="voice-source">${icon('mic')}</span>` : ''}</span></span><span class="transaction-amount ${tone}">${sign}${formatMoney(entry.amountMinor)}</span></button>`;
}
function empty(title = '还没有账目', subtitle = '从今天的第一笔开始吧。') { return `<div class="empty-state">${icon('ledger')}<strong>${esc(title)}</strong><p>${esc(subtitle)}</p></div>`; }
function home() {
  const today = dateKey(); const month = periodFor('month', today); const summary = summarize(state.entries, month);
  const todayEntries = filterEntries(state.entries, '').filter(entry => entry.occurredOn === today).slice(0, 4);
  const todaySummary = summarize(state.entries, { start: today, end: today });
  const amount = formatMoney(summary.expense).split('.');
  const dateLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
  return `<main class="page"><div class="greeting"><span>${esc(dateLabel)}</span><span class="sample-dot">${isSample() ? '示例账本' : '个人账本 · 本地保存'}</span></div><section class="overview"><div class="overview-top"><span class="overview-label">本月净支出</span><button class="month-chip" data-action="tab" data-value="stats">${Number(today.slice(5,7))}月${icon('down')}</button></div><div class="hero-money"><span class="currency">¥</span><strong>${amount[0]}<span class="decimals">.${amount[1]}</span></strong></div><div class="overview-bottom"><div><div class="mini-label">${icon('income')}本月收入</div><div class="mini-money">${formatMoney(summary.income)}</div></div><div><div class="mini-label">收支结余</div><div class="mini-money">${formatMoney(summary.balance)}</div></div></div></section><section class="voice-section"><div class="section-heading"><h2>说一句，记一笔</h2><span class="subtle-pill"><i></i>语音优先</span></div><button class="voice-button" data-action="voice-hold" aria-label="按住说话，预览语音记账交互"><span class="mic-disc">${icon('mic')}</span><span class="voice-copy"><strong>按住说话</strong><small>松开生成账单，上滑取消</small></span><span class="voice-waves">${'<i></i>'.repeat(7)}</span></button><p class="example-line">试试说：“午餐吃了盖饭，花了15元”</p><div class="input-shortcuts"><button class="shortcut" data-action="manual">${icon('edit')}手动记账</button><button class="shortcut" data-action="text">${icon('text')}文字记账</button></div></section><section class="today-section"><div class="section-heading"><h2>今天的记录</h2><button class="text-button" data-action="tab" data-value="ledger">全部账单${icon('chevron')}</button></div><div class="daily-caption">${todayEntries.length ? `支出 ¥${formatMoney(todaySummary.expense)} · ${todaySummary.count}笔记录` : '每一笔，都值得被记下'}</div>${todayEntries.length ? todayEntries.map(transaction).join('') : empty('从第一笔开始', '按住说话，或者手动记下今天的消费。')}</section></main>`;
}
function ledgerResults() {
  let entries = filterEntries(state.entries, state.search, state.kind, state.category);
  if (state.drillPeriod) entries = entries.filter(entry => entry.occurredOn >= state.drillPeriod.start && entry.occurredOn <= state.drillPeriod.end);
  if (!entries.length) return empty(state.search || state.category ? '没有找到相关账目' : '还没有账目', '换个关键词，或记下新的一笔。');
  const dates = [...new Set(entries.map(entry => entry.occurredOn))];
  return dates.map(day => {
    const group = entries.filter(entry => entry.occurredOn === day);
    const total = summarize(group, { start: day, end: day });
    return `<div class="group-heading"><span>${day === dateKey() ? '今天' : esc(day.slice(5).replace('-', '月') + '日')}</span><small>支出 ${formatMoney(total.expense)}</small></div>${group.map(transaction).join('')}`;
  }).join('');
}
function ledger() {
  return `<main class="page"><label class="search-box">${icon('search')}<input id="search" type="search" placeholder="搜索名称、分类或金额" value="${esc(state.search)}" aria-label="搜索账单"></label><div class="filter-row">${[['all','全部'],['expense','支出'],['income','收入'],['refund','退款'],['transfer','转账'],['debt','往来']].map(([kind,name]) => `<button class="${state.kind === kind ? 'active' : ''}" data-action="filter-kind" data-value="${kind}">${name}</button>`).join('')}</div>${state.category || state.drillPeriod ? `<div class="filter-tag"><span>${state.category ? esc(categoryById(state.category).name) : '全部分类'}${state.drillPeriod ? ' · ' + esc(state.drillPeriod.label) : ''}</span><button data-action="clear-filter" aria-label="清除筛选">${icon('close')}</button></div>` : ''}<div class="ledger-results">${ledgerResults()}</div></main>`;
}
function chart() {
  const period = currentPeriod(); const points = trendPoints(state.entries, period);
  const maxValue = Math.max(100, ...points.map(point => Math.max(point.expense, point.income)));
  const step = 252 / points.length; const barWidth = Math.max(2, Math.min(6, step * .26));
  return `<svg class="chart" viewBox="0 0 284 140" role="img" aria-label="${esc(period.label)}收入与支出趋势"><path d="M25 14H280M25 61H280M25 108H280" stroke="#E7ECDD" stroke-dasharray="3 4" fill="none"/><text x="0" y="17">${maxValue >= 100000 ? Math.ceil(maxValue/100000) + 'k' : Math.ceil(maxValue / 100)}</text><text x="8" y="111">0</text>${points.map((point,index) => {
    const x = 27 + index * step; const expenseHeight = point.expense / maxValue * 88; const incomeHeight = point.income / maxValue * 88;
    const showLabel = points.length <= 12 || index % 5 === 0 || index === points.length - 1;
    return `<g data-action="chart-drill" data-value="${point.date}" style="cursor:pointer"><rect x="${x}" y="${108-expenseHeight}" width="${barWidth}" height="${expenseHeight}" rx="${barWidth/2}" fill="#92A87C"/><rect x="${x+barWidth+1}" y="${108-incomeHeight}" width="${barWidth}" height="${incomeHeight}" rx="${barWidth/2}" fill="#D7BD95"/><rect x="${x-2}" y="10" width="${step}" height="100" fill="transparent"/><title>${point.date} 支出 ${formatMoney(point.expense)} 收入 ${formatMoney(point.income)}</title>${showLabel ? `<text x="${x+barWidth}" y="128" text-anchor="middle">${esc(point.label)}</text>` : ''}</g>`;
  }).join('')}</svg>`;
}
function stats() {
  const period = currentPeriod(); const summary = summarize(state.entries, period); const ranking = categoryTotals(state.entries, period, state.rankingKind);
  return `<main class="page"><div class="segment">${[['week','周'],['month','月'],['year','年']].map(([mode,label]) => `<button data-action="period-mode" data-value="${mode}" class="${state.mode === mode ? 'active' : ''}">${label}</button>`).join('')}</div><div class="period-picker"><button data-action="period-shift" data-value="-1" aria-label="上一期">${icon('back')}</button><span>${esc(period.label)}</span><button data-action="period-shift" data-value="1" aria-label="下一期" ${state.offset >= 0 ? 'disabled' : ''}>${icon('chevron')}</button></div><section class="stats-summary"><div><small>消费支出</small><strong>${formatMoney(summary.expense)}</strong></div><div><small>收入</small><strong>${formatMoney(summary.income)}</strong></div><div><small>收支结余</small><strong>${formatMoney(summary.balance)}</strong></div></section><section class="chart-card"><div class="card-heading"><span>收支趋势</span><span class="legend"><span><i></i>支出</span><span><i></i>收入</span></span></div>${chart()}<p class="stats-fineprint">点击柱形查看明细 · 转账与借还款本金不计入收支</p></section>${summary.refund ? `<p class="refund-info">本期退款 ¥${formatMoney(summary.refund)} · 净支出 ¥${formatMoney(summary.netExpense)}</p>` : ''}<section class="category-summary"><div class="section-heading"><h2>${state.rankingKind === 'expense' ? '钱都花在哪' : '收入来自哪里'}</h2><button class="text-button" data-action="ranking-kind">${state.rankingKind === 'expense' ? '查看收入' : '查看支出'}${icon('chevron')}</button></div>${ranking.length ? ranking.map(item => `<button class="rank-button" data-action="category-drill" data-value="${item.category.id}"><div class="rank-row"><span class="category-icon" style="color:${item.category.color};background:${item.category.background}">${icon(item.category.icon)}</span><span><span class="rank-label">${esc(item.category.name)}<small>${(item.ratio*100).toFixed(1)}%</small></span><span class="rank-track"><i style="width:${item.ratio*100}%"></i></span></span><span class="rank-amount">${formatMoney(item.amount)}</span></div></button>`).join('') : empty('这段时间还没有记录', '记下一笔后，就能在这里看到变化。')}</section></main>`;
}
function settings() {
  const row = (symbol,title,subtitle,action) => `<button class="setting-row" data-action="${action}">${icon(symbol)}<span class="setting-copy"><strong>${title}</strong><small>${subtitle}</small></span>${icon('chevron')}</button>`;
  return `<main class="page"><div class="settings-brand"><img src="./assets/app_icon.svg" alt=""><strong>一语账</strong><p>说一句，记一笔。</p></div><section class="settings-card">${row('shield','个人账本','账目保存在本机，随时导出','privacy')}${row('mic','语音服务','在原生客户端中连接云端识别','connection')}</section><section class="settings-card">${row('export','导出 CSV','用于查看、分析和分享','export-csv')}${row('export','导出完整备份','保留账目编号与关联信息','export-backup')}${row('import','恢复备份','合并新账目，相同编号不覆盖','import-backup')}</section><section class="settings-card">${row('ledger',isSample() ? '清除示例数据' : '体验示例账本',isSample() ? '保留你自己创建的账目' : '查看界面效果，示例会明确标注','sample-data')}</section><p class="preview-disclosure" style="text-align:center;margin-top:25px">交互设计预览 · v0.1<br>实时语音识别在鸿蒙客户端接入，<br>本预览的语音结果为明确标注的示例。</p><input type="file" id="backup-file" accept="application/json,.json" hidden></main>`;
}
function editorSheet() {
  const e = state.editor; const categories = categoriesForKind(e.kind);
  return `<div class="scrim" data-action="dismiss"><section class="sheet" role="dialog" aria-modal="true" aria-label="${e.existing ? '编辑账目' : '确认账目'}" data-sheet><div class="drag-handle"></div><div class="sheet-heading"><h2>${e.existing ? '编辑这一笔' : state.transcript ? '听懂了，确认一下' : '记下这一笔'}</h2><button class="close-button" data-action="close" aria-label="关闭">${icon('close')}</button></div>${state.transcript ? `<div class="transcript-box">${icon('mic')}“${esc(state.transcript)}”<br><span class="demo-badge">示例识别结果 · 请核对</span></div>` : ''}<form id="entry-form"><div class="form-kinds">${[['expense','支出'],['income','收入'],['refund','退款'],['transfer','转账'],['debt','往来']].map(([kind,name])=>`<button type="button" class="${e.kind===kind?'active':''}" data-action="editor-kind" data-value="${kind}">${name}</button>`).join('')}</div><label class="amount-field"><span>¥</span><input id="amount" name="amount" inputmode="decimal" placeholder="0.00" value="${esc(e.amount)}" aria-label="金额（元）" autocomplete="off" required></label><div class="field-label">名称</div><input class="form-input" name="title" maxlength="80" placeholder="这笔钱用在了哪里？" value="${esc(e.title)}" aria-label="账目名称" required><div class="field-label">分类</div><div class="category-picker">${categories.map(c=>`<button type="button" class="category-option ${e.categoryId===c.id?'active':''}" data-action="editor-category" data-value="${c.id}">${icon(c.icon)}${esc(c.name)}</button>`).join('')}</div><div class="form-two"><div><div class="field-label">日期</div><input class="form-input" name="occurredOn" type="date" value="${esc(e.occurredOn)}" aria-label="发生日期" required></div><div><div class="field-label">备注 · 选填</div><input class="form-input" name="note" maxlength="500" placeholder="留一点细节" value="${esc(e.note)}" aria-label="备注"></div></div><div class="field-error" role="alert">${esc(state.error)}</div><button class="primary-button" type="submit">${icon('check')}${e.existing ? '保存修改' : state.queue.length ? '保存并确认下一笔' : '确认，记下这一笔'}</button>${e.existing ? '<button type="button" class="secondary-button danger-button" data-action="delete">删除这笔账目</button>' : ''}</form></section></div>`;
}
function voiceSheet() { return `<div class="scrim"><section class="sheet voice-sheet" role="dialog" aria-modal="true" aria-label="语音交互演示"><div class="drag-handle"></div><div class="sheet-heading"><h2>把这一笔，说给我听</h2><button class="close-button" data-action="close" aria-label="取消">${icon('close')}</button></div><p class="recording-caption">交互演示 · 未开启麦克风</p><div class="big-mic">${icon('mic')}</div><div class="live-wave">${'<i></i>'.repeat(21)}</div><div class="voice-transcript">中午吃了辣椒炒肉盖饭，<br>花了十五元。</div><div class="voice-hint">${state.holding ? '松开确认，上滑取消' : '本预览使用示例语音，原生客户端支持真实录音'}</div>${!state.holding ? '<button class="primary-button" data-action="voice-example">查看示例识别结果</button>' : ''}</section></div>`; }
function textSheet() { return `<div class="scrim" data-action="dismiss"><section class="sheet" data-sheet role="dialog" aria-modal="true" aria-label="文字记账"><div class="drag-handle"></div><div class="sheet-heading"><h2>一句话，也能记账</h2><button class="close-button" data-action="close" aria-label="关闭">${icon('close')}</button></div><p class="sheet-note">预览中演示简单金额提取，云端理解在原生客户端接入。</p><textarea class="form-input text-entry" id="natural-text" placeholder="例如：午餐吃了盖饭，花了15元" aria-label="记账内容"></textarea><button class="text-button" data-action="fill-example" style="margin-top:10px">填入示例：盖饭15元，打车22元</button><div class="field-error">${esc(state.error)}</div><button class="primary-button" data-action="parse-text">生成账目</button></section></div>`; }
function render() {
  app.innerHTML = topbar() + (state.tab === 'home' ? home() : state.tab === 'ledger' ? ledger() : state.tab === 'stats' ? stats() : settings()) + (state.tab !== 'settings' ? navigation() : '') + (state.modal === 'editor' ? editorSheet() : state.modal === 'voice' ? voiceSheet() : state.modal === 'text' ? textSheet() : '');
}
function voiceExample() {
  const entry = newEntry(); Object.assign(entry, { amountMinor: 1500, categoryId: 'food', title: '辣椒炒肉盖饭', timeHint: '中午', source: 'sample' });
  openEditor(entry, '中午吃了辣椒炒肉盖饭，花了十五元');
}
function saveEditor() {
  const e = state.editor;
  const entry = { id:e.id, kind:e.kind, amountMinor:parseMinor(e.amount), currency:'CNY', categoryId:e.categoryId, title:e.title.trim(), occurredOn:e.occurredOn, timeHint:e.timeHint, note:e.note.trim(), source:e.source, groupId:e.groupId, linkedEntryId:e.linkedEntryId, createdAt:e.createdAt, updatedAt:Date.now(), deletedAt:0 };
  const error = validateEntry(entry);
  if (error) { state.error = error; document.querySelector('.field-error').textContent = error; return; }
  const existing = state.entries.findIndex(item => item.id === entry.id);
  if (existing >= 0) state.entries[existing] = entry; else state.entries.push(entry);
  persist();
  if (state.queue.length) { openEditor(state.queue.shift(), state.transcript); toast('已保存，继续确认下一笔'); }
  else { state.modal = null; state.editor = null; render(); toast('已记下这一笔'); }
}
function download(content, filename, mime) {
  const url = URL.createObjectURL(new Blob([content], { type:mime })); const a = document.createElement('a'); a.href=url; a.download=filename; a.click(); setTimeout(()=>URL.revokeObjectURL(url), 1000); toast('已导出');
}
document.addEventListener('submit', event => { if (event.target.id === 'entry-form') { event.preventDefault(); saveEditor(); } });
document.addEventListener('input', event => {
  if (event.target.id === 'search') { state.search=event.target.value; document.querySelector('.ledger-results').innerHTML=ledgerResults(); }
  if (state.editor && event.target.closest('#entry-form') && event.target.name) state.editor[event.target.name]=event.target.value;
});
document.addEventListener('change', async event => {
  if (event.target.id !== 'backup-file' || !event.target.files[0]) return;
  try {
    const backup = parseBackupDocument(await event.target.files[0].text());
    if (backup.categories.length || backup.monthlyBudgets.length) throw new Error('此早期预览不支持自定义分类和月预算，请在手机客户端恢复该备份');
    const ids = new Set(state.entries.map(entry=>entry.id)); const fresh=backup.entries.filter(entry=>!ids.has(entry.id));
    state.entries.push(...fresh); persist(); render(); toast(`已恢复 ${fresh.length} 笔，相同编号未覆盖`);
  } catch (error) { toast(error.message || '备份格式不正确'); }
});
document.addEventListener('pointerdown', event => {
  const button=event.target.closest('[data-action="voice-hold"]'); if(!button) return;
  event.preventDefault(); state.holding=true; state.holdStart=Date.now(); state.holdY=event.clientY; state.cancelled=false; state.modal='voice'; render();
});
document.addEventListener('pointermove', event => {
  if(!state.holding) return; state.cancelled=state.holdY-event.clientY>70;
  const hint=document.querySelector('.voice-hint'); if(hint) hint.textContent=state.cancelled?'松开取消这段录音':'松开确认，上滑取消';
});
document.addEventListener('pointerup', () => {
  if(!state.holding) return; state.holding=false;
  if(state.cancelled) { state.modal=null; render(); toast('已取消'); }
  else if(Date.now()-state.holdStart>250) voiceExample(); else render();
});
document.addEventListener('pointercancel', () => { if(state.holding){state.holding=false;state.modal=null;render();} });
document.addEventListener('click', event => {
  const target=event.target.closest('[data-action]'); if(!target) return;
  const action=target.dataset.action, value=target.dataset.value;
  if(action==='dismiss' && event.target.closest('[data-sheet]')) return;
  if(action==='voice-hold') return;
  if(action==='tab'){state.tab=value;state.modal=null;if(value==='ledger'){state.category='';state.drillPeriod=null;}render();}
  else if(action==='manual') {state.queue=[];openEditor();}
  else if(action==='edit'){state.queue=[];openEditor(state.entries.find(entry=>entry.id===value));}
  else if(action==='text'){state.error='';state.modal='text';render();}
  else if(action==='close'||action==='dismiss'){state.modal=null;state.holding=false;state.queue=[];render();}
  else if(action==='editor-kind'){state.editor.kind=value;const available=categoriesForKind(value);if(!available.some(c=>c.id===state.editor.categoryId))state.editor.categoryId=available[0].id;render();}
  else if(action==='editor-category'){state.editor.categoryId=value;render();}
  else if(action==='filter-kind'){state.kind=value;render();}
  else if(action==='clear-filter'){state.category='';state.drillPeriod=null;render();}
  else if(action==='period-mode'){state.mode=value;state.offset=0;render();}
  else if(action==='period-shift'){state.offset=Math.min(0,state.offset+Number(value));render();}
  else if(action==='ranking-kind'){state.rankingKind=state.rankingKind==='expense'?'income':'expense';render();}
  else if(action==='category-drill'){state.category=value;state.kind=state.rankingKind;state.drillPeriod=currentPeriod();state.search='';state.tab='ledger';render();}
  else if(action==='chart-drill'){state.category='';state.kind='all';state.search='';state.drillPeriod=state.mode==='year'?periodFor('month',value):{start:value,end:value,label:value};state.tab='ledger';render();}
  else if(action==='voice-example')voiceExample();
  else if(action==='fill-example')document.getElementById('natural-text').value='盖饭15元，打车22元';
  else if(action==='parse-text'){
    const text=document.getElementById('natural-text').value.trim();
    const pieces=text.split(/[，,；;。\n]/).filter(Boolean); const drafts=[];
    for(const piece of pieces){const match=piece.match(/(\d+(?:\.\d{1,2})?)\s*(?:元|块)/);if(!match)continue;const entry=newEntry();entry.source='sample';entry.amountMinor=parseMinor(match[1]);entry.title=piece.replace(match[0],'').replace(/^(我|今天|中午|午餐|吃了|花了|花|收到|到账)+/g,'').trim()||'这笔账目';if(/工资|奖金|收入/.test(piece)){entry.kind='income';entry.categoryId=/奖金/.test(piece)?'bonus':'salary';}else if(/打车|地铁|公交/.test(piece))entry.categoryId='transport';drafts.push(entry);}
    if(!drafts.length){state.error='请补充明确金额，例如“盖饭15元”';document.querySelector('.field-error').textContent=state.error;return;}
    state.queue=drafts.slice(1);openEditor(drafts[0],text);
  }
  else if(action==='delete'){
    if (!window.confirm('删除这笔账目？删除后无法撤销，已导出文件会保留。')) return;
    const id=state.editor.id;
    state.entries=state.entries.filter(entry=>entry.id!==id).map(entry=>entry.linkedEntryId===id ? {...entry,linkedEntryId:''} : entry);
    persist();state.modal=null;state.editor=null;state.transcript='';render();toast('已删除这笔账目');
  }
  else if(action==='export-csv')download(exportCsv(state.entries),`一语账-${dateKey()}.csv`,'text/csv;charset=utf-8');
  else if(action==='export-backup')download(exportBackup(state.entries),`一语账-备份-${dateKey()}.json`,'application/json');
  else if(action==='import-backup')document.getElementById('backup-file').click();
  else if(action==='sample-data'||action==='toggle-data'){
    if(isSample())state.entries=state.entries.filter(entry=>entry.source!=='sample');else state.entries.push(...seedEntries().filter(sample=>!state.entries.some(entry=>entry.id===sample.id)));persist();render();document.querySelector('[data-action="toggle-data"]').textContent=isSample()?'查看空账本':'加载示例账本';toast(isSample()?'示例账本已加载':'已清除示例数据');
  }
  else if(action==='privacy')toast('原生客户端账目保存在本机，支持导出与恢复');
  else if(action==='connection')toast('此页面是交互预览，真实语音在鸿蒙客户端接入');
});
render();
