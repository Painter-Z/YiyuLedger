export interface LedgerLayout {
  pageWidth: number;
  sheetWidth: number;
  padding: number;
  quickCount: number;
  categoryColumns: number;
  controlHeight: number;
}

export function ledgerLayout(width: number, fontScale: number = 1): LedgerLayout {
  const usable: number = Math.max(240, width);
  const scale: number = Math.max(1, Math.min(3.2, fontScale));
  const sheet: number = Math.min(600, usable);
  const padding: number = sheet < 360 ? 16 : 24;
  const inner: number = sheet - padding * 2;
  return { pageWidth: Math.min(720, usable), sheetWidth: sheet, padding: padding,
    quickCount: Math.max(1, Math.min(3, Math.floor(inner / (68 * scale)) - 1)),
    categoryColumns: Math.max(2, Math.min(5, Math.floor(inner / (76 * scale)))),
    controlHeight: Math.max(48, Math.ceil(22 * scale + 20)) };
}
