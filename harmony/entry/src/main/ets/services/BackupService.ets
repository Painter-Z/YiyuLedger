import { common } from '@kit.AbilityKit';
import { picker, fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { exportBackup, exportCsv, BackupDocument, CustomCategory, LedgerEntry, MonthlyBudget, parseBackupDocument, dateKey } from '../model/Ledger';

export class BackupService {
  static async save(context: common.UIAbilityContext, entries: LedgerEntry[], csv: boolean, custom: CustomCategory[] = [], budgets: MonthlyBudget[] = []): Promise<boolean> {
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    options.newFileNames = ['一语账_' + dateKey() + (csv ? '.csv' : '.json')];
    const paths: string[] = await documentPicker.save(options);
    if (paths.length === 0) return false;
    const file: fileIo.File = fileIo.openSync(paths[0], fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    const content: string = csv ? exportCsv(entries, custom) : exportBackup(entries, new Date().toISOString(), custom, budgets);
    try { fileIo.writeSync(file.fd, content); } finally { fileIo.closeSync(file); }
    return true;
  }

  static async read(context: common.UIAbilityContext): Promise<BackupDocument | undefined> {
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    const paths: string[] = await documentPicker.select(options);
    if (paths.length === 0) return undefined;
    const file: fileIo.File = fileIo.openSync(paths[0], fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      if (size > 5 * 1024 * 1024) throw new Error('备份文件过大');
      const buffer: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buffer);
      const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
      return parseBackupDocument(decoder.decodeWithStream(new Uint8Array(buffer)));
    } finally { fileIo.closeSync(file); }
  }
}
