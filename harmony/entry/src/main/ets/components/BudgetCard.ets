import { BudgetProgress } from '../model/Budget';
import { formatMoney } from '../model/Ledger';
import { PALETTE } from '../model/Palette';

@Component
export struct BudgetCard {
  @Prop @Watch('redraw') progress: BudgetProgress;
  @Prop widthAvailable: number = 300;
  @Prop fontScale: number = 1;
  onSetup: () => void = () => {};
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private canvasSize: number = 200;

  private ringSize(): number { return Math.max(140, Math.min(208, this.widthAvailable - 36)); }
  private ringStrokeWidth(): number { return Math.min(22, this.ringSize() * 0.11); }
  private centerWidth(): number { return this.ringSize() - this.ringStrokeWidth() * 2 - 20; }
  private centerLabel(): string {
    return this.progress.state === 'unset' ? '设置月预算' : this.progress.state === 'over' ? '已超支' :
      this.progress.state === 'reached' ? '额度已用完' : '剩余可用';
  }
  private amount(): string { return formatMoney(this.progress.state === 'over' ? this.progress.overspent : this.progress.remaining); }
  private amountFont(): number {
    return Math.max(11, Math.min(26 * Math.max(1, this.fontScale), (this.centerWidth() - 8) / ((this.amount().length + 2) * 0.62)));
  }
  private usedLabel(): string {
    return this.progress.usedRatio > 99.99 ? '已用超过9999%' : '已用 ' + (this.progress.usedRatio * 100).toFixed(1).replace(/\.0$/, '') + '%';
  }
  private stackAmounts(): boolean {
    return (this.widthAvailable - 36) / Math.max(1, this.fontScale) < 275 ||
      Math.max(formatMoney(this.progress.limit).length, formatMoney(this.progress.spent).length) > 12;
  }
  private arc(ratio: number, color: string): void {
    if (ratio <= 0) return;
    const c: CanvasRenderingContext2D = this.context;
    c.strokeStyle = color; c.beginPath();
    c.arc(this.canvasSize / 2, this.canvasSize / 2, this.canvasSize / 2 - this.ringStrokeWidth() / 2 - 6,
      -Math.PI / 2, -Math.PI / 2 + ratio * 2 * Math.PI);
    c.stroke();
  }
  redraw(): void {
    const c: CanvasRenderingContext2D = this.context;
    c.clearRect(0, 0, this.canvasSize, this.canvasSize); c.lineWidth = this.ringStrokeWidth(); c.lineCap = 'round';
    this.arc(1, PALETTE.sage);
    this.arc(this.progress.baseRatio, PALETTE.expense);
    // A second lap covers the completed green ring. At >=200% it remains red, with exact excess in text.
    this.arc(this.progress.overRatio, PALETTE.error);
  }
  @Builder
  metric(isLimit: boolean) {
    Column({ space: 6 }) {
      Text(isLimit ? '每月预算' : '已消费').fontSize(11).fontColor(PALETTE.muted)
      Text(isLimit ? (this.progress.limit > 0 ? '¥ ' + formatMoney(this.progress.limit) : '尚未设置') : '¥ ' + formatMoney(this.progress.spent))
        .fontSize(18).fontWeight(FontWeight.Medium).fontColor(PALETTE.ink)
        .width('100%').maxLines(1).minFontSize('12vp').maxFontSize(18)
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  build() {
    Column({ space: 16 }) {
      Row({ space: 8 }) {
        Text(this.progress.month.slice(0, 4) + '年' + Number(this.progress.month.slice(5)) + '月预算')
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(PALETTE.forest).layoutWeight(1)
        Button(this.progress.limit > 0 ? '调整' : '设置').fontSize(12).fontColor(PALETTE.forest)
          .height(Math.max(44, 22 * this.fontScale + 18)).padding({ left: 16, right: 16 }).backgroundColor(PALETTE.field)
          .onClick(this.onSetup).id('setupMonthlyBudget').accessibilityText('设置' + this.progress.month + '月预算')
      }.width('100%')
      Stack() {
        Canvas(this.context).width('100%').height('100%').onReady(() => this.redraw())
          .onAreaChange((oldArea: Area, area: Area) => { this.canvasSize = Number(area.width); this.redraw(); })
          .accessibilityLevel('no')
        Column({ space: 7 }) {
          Text(this.centerLabel()).fontSize(11).fontColor(this.progress.state === 'over' ? PALETTE.error : PALETTE.muted)
            .maxLines(2).textAlign(TextAlign.Center)
          if (this.progress.limit > 0) {
            Text('¥ ' + this.amount()).fontSize(this.amountFont() + 'vp').fontWeight(FontWeight.Medium)
              .fontColor(this.progress.state === 'over' ? PALETTE.error : PALETTE.forest).maxLines(1).width('100%').textAlign(TextAlign.Center)
            Text(this.usedLabel()).fontSize(10).fontColor(PALETTE.muted).maxLines(1).minFontSize('10vp').maxFontSize(10)
          } else {
            Text('让花费心中有数').fontSize(10).fontColor(PALETTE.muted).textAlign(TextAlign.Center)
          }
        }.width(this.centerWidth()).padding({ left: 4, right: 4 })
      }.width(this.ringSize()).height(this.ringSize()).id('monthlyBudgetRing')
      if (this.stackAmounts()) {
        Column({ space: 14 }) {
          this.metric(true)
          this.metric(false)
        }.width('100%')
      } else {
        Row({ space: 16 }) {
          Column() { this.metric(true) }.layoutWeight(1)
          Column() { this.metric(false) }.layoutWeight(1)
        }.width('100%')
      }
      Text('当月退款抵减消费；收入、转账和往来不占预算。').fontSize(10).fontColor(PALETTE.muted)
        .lineHeight(18 * Math.max(1, this.fontScale)).width('100%')
    }.width('100%').padding(18).backgroundColor(PALETTE.card).borderRadius(22)
      .border({ width: 1, color: PALETTE.outline }).margin({ top: 20 })
  }
}
