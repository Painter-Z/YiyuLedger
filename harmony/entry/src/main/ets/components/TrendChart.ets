import { TrendPoint, formatMoney } from '../model/Ledger';
import { PALETTE } from '../model/Palette';

@Component
export struct TrendChart {
  @Prop @Watch('redraw') points: TrendPoint[] = [];
  onSelect: (date: string) => void = () => {};
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private chartWidth: number = 300;
  private chartHeight: number = 190;
  private touchX: number = 0;
  private touchY: number = 0;
  private touchMoved: boolean = false;

  redraw(): void {
    const c: CanvasRenderingContext2D = this.context;
    const w: number = this.chartWidth;
    const h: number = this.chartHeight;
    const left: number = 38;
    const top: number = 18;
    const bottom: number = h - 27;
    const width: number = w - left - 8;
    const largest: number = this.points.reduce((value: number, p: TrendPoint) => Math.max(value, p.expense, p.income), 0);
    const scale: number = largest > 0 ? Math.pow(10, Math.floor(Math.log10(largest))) : 10000;
    const maximum: number = largest > 0 ? Math.ceil(largest / scale) * scale : 10000;
    c.clearRect(0, 0, w, h);
    c.font = '10vp sans-serif';
    c.textBaseline = 'middle';
    for (let i: number = 0; i < 4; i++) {
      const y: number = top + (bottom - top) * i / 3;
      c.strokeStyle = PALETTE.outline;
      c.beginPath(); c.moveTo(left, y); c.lineTo(w - 8, y); c.stroke();
      c.fillStyle = PALETTE.muted;
      c.textAlign = 'right';
      const amount: number = Math.round(maximum * (3 - i) / 3);
      const label: string = amount >= 1000000 ? (amount / 1000000).toFixed(1) + '万' :
        maximum >= 10000 ? String(Math.round(amount / 100)) : formatMoney(amount).replace(/\.00$/, '');
      c.fillText(label, left - 6, y);
    }
    if (this.points.length === 0) return;
    const step: number = width / this.points.length;
    const bar: number = Math.max(2, Math.min(10, step * 0.28));
    this.points.forEach((point: TrendPoint, index: number) => {
      const x: number = left + step * (index + 0.5);
      const expenseHeight: number = point.expense / maximum * (bottom - top);
      const incomeHeight: number = point.income / maximum * (bottom - top);
      c.fillStyle = PALETTE.expense;
      if (point.expense > 0) c.fillRect(x - bar - 1, bottom - Math.max(2, expenseHeight), bar, Math.max(2, expenseHeight));
      c.fillStyle = PALETTE.income;
      if (point.income > 0) c.fillRect(x + 1, bottom - Math.max(2, incomeHeight), bar, Math.max(2, incomeHeight));
      if (this.points.length <= 12 || index % 5 === 0 || index === this.points.length - 1) {
        c.textAlign = 'center'; c.fillStyle = PALETTE.muted;
        c.fillText(point.label, x, h - 10);
      }
    });
  }

  build() {
    Column() {
      Canvas(this.context)
        .width('100%').height(190)
        .onReady(() => this.redraw())
        .onAreaChange((oldArea: Area, area: Area) => {
          this.chartWidth = Number(area.width); this.chartHeight = Number(area.height); this.redraw();
        })
        .onTouch((event: TouchEvent) => {
          if (event.changedTouches.length === 0) return;
          if (event.type === TouchType.Down) {
            this.touchX = event.changedTouches[0].x; this.touchY = event.changedTouches[0].y; this.touchMoved = false;
          }
          if (event.type === TouchType.Move && (Math.abs(event.changedTouches[0].x - this.touchX) > 8 ||
            Math.abs(event.changedTouches[0].y - this.touchY) > 8)) this.touchMoved = true;
          if (event.type !== TouchType.Up || this.touchMoved || this.points.length === 0) return;
          const x: number = event.changedTouches[0].x;
          const index: number = Math.floor((x - 38) / ((this.chartWidth - 46) / this.points.length));
          if (index >= 0 && index < this.points.length) this.onSelect(this.points[index].date);
        })
        .accessibilityText('收支趋势图，点击柱形可查看对应日期的账单')
      Row({ space: 14 }) {
        Row({ space: 5 }) { Column().width(6).height(6).backgroundColor(PALETTE.expense).borderRadius(3); Text('支出').fontSize(11).fontColor(PALETTE.muted); }
        Row({ space: 5 }) { Column().width(6).height(6).backgroundColor(PALETTE.income).borderRadius(3); Text('收入').fontSize(11).fontColor(PALETTE.muted); }
        Blank()
        Text('点按查看明细').fontSize(10).fontColor(PALETTE.muted)
      }.width('100%')
    }
  }
}
