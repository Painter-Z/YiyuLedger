import { Configuration, UIAbility } from '@kit.AbilityKit';
import { KeyboardAvoidMode, window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { PALETTE } from '../model/Palette';

export default class EntryAbility extends UIAbility {
  onBackground(): void { this.context.eventHub.emit('yiyuBackground'); }
  onForeground(): void { this.context.eventHub.emit('yiyuForeground'); }
  onConfigurationUpdate(configuration: Configuration): void {
    if (configuration.fontSizeScale !== undefined) this.context.eventHub.emit('yiyuFontScale', configuration.fontSizeScale);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error: BusinessError) => {
      if (error.code) return;
      windowStage.getMainWindow().then((main: window.Window) => {
        main.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
        main.setPreferredOrientation(window.Orientation.AUTO_ROTATION_RESTRICTED).catch(() => {});
        main.setWindowSystemBarProperties({
          statusBarColor: PALETTE.background, statusBarContentColor: PALETTE.forest,
          navigationBarColor: PALETTE.background, navigationBarContentColor: PALETTE.forest
        }).catch(() => {});
      }).catch(() => {});
    });
  }
}
