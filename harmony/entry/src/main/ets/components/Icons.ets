export function iconResource(name: string): Resource {
  switch (name) {
    case 'mic': return $r('app.media.ic_mic');
    case 'home': return $r('app.media.ic_home');
    case 'ledger': return $r('app.media.ic_ledger');
    case 'chart': return $r('app.media.ic_chart');
    case 'settings': return $r('app.media.ic_settings');
    case 'food': return $r('app.media.ic_food');
    case 'car': return $r('app.media.ic_car');
    case 'bag': return $r('app.media.ic_bag');
    case 'basket': return $r('app.media.ic_basket');
    case 'play': return $r('app.media.ic_play');
    case 'heart': return $r('app.media.ic_heart');
    case 'book': return $r('app.media.ic_book');
    case 'plane': return $r('app.media.ic_plane');
    case 'gift': return $r('app.media.ic_gift');
    case 'wallet': return $r('app.media.ic_wallet');
    case 'briefcase': return $r('app.media.ic_briefcase');
    case 'trend': return $r('app.media.ic_trend');
    case 'swap': return $r('app.media.ic_swap');
    case 'edit': return $r('app.media.ic_edit');
    case 'text': return $r('app.media.ic_text');
    case 'chevron': return $r('app.media.ic_chevron');
    case 'back': return $r('app.media.ic_back');
    case 'plus': return $r('app.media.ic_plus');
    case 'close': return $r('app.media.ic_close');
    case 'check': return $r('app.media.ic_check');
    case 'search': return $r('app.media.ic_search');
    case 'income': return $r('app.media.ic_income');
    case 'export': return $r('app.media.ic_export');
    case 'import': return $r('app.media.ic_import');
    case 'shield': return $r('app.media.ic_shield');
    case 'calendar': return $r('app.media.ic_calendar');
    default: return $r('app.media.ic_dots');
  }
}
