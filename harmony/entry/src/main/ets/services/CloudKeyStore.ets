import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';

class CloudKeyStore {
  private alias(): Uint8Array { return new util.TextEncoder().encodeInto('yiyu.personal.dashscope.v1'); }
  private query(returnType: asset.ReturnType): asset.AssetMap {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias());
    query.set(asset.Tag.RETURN_TYPE, returnType);
    return query;
  }
  async hasKey(): Promise<boolean> {
    try { return (await asset.query(this.query(asset.ReturnType.ATTRIBUTES))).length > 0; }
    catch (error) {
      if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return false;
      throw new Error('系统加密存储暂不可用，请解锁手机后重试');
    }
  }
  async getKey(): Promise<string> {
    let results: asset.AssetMap[];
    try { results = await asset.query(this.query(asset.ReturnType.ALL)); }
    catch (error) {
      if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) throw new Error('请在设置中配置自己的百炼 API Key');
      throw new Error('无法读取加密密钥，请解锁手机后重试');
    }
    if (!results.length) throw new Error('请在设置中配置自己的百炼 API Key');
    const value: Uint8Array = results[0].get(asset.Tag.SECRET) as Uint8Array;
    return util.TextDecoder.create('utf-8').decodeToString(value);
  }
  validateKey(value: string): string {
    const key: string = value.trim();
    if (!/^sk-[A-Za-z0-9._-]{16,1020}$/.test(key)) throw new Error('API Key 格式不正确，请核对后重试');
    return key;
  }
  async saveKey(value: string): Promise<void> {
    const key: string = this.validateKey(value);
    const attributes: asset.AssetMap = new Map();
    attributes.set(asset.Tag.ALIAS, this.alias());
    attributes.set(asset.Tag.SECRET, new util.TextEncoder().encodeInto(key));
    attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
    attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
    attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
    try { await asset.add(attributes); }
    catch (error) { throw new Error('密钥未能存入系统加密存储，请解锁手机后重试'); }
  }
  async removeKey(): Promise<void> {
    const query: asset.AssetMap = new Map(); query.set(asset.Tag.ALIAS, this.alias());
    try { await asset.remove(query); }
    catch (error) { if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw new Error('移除密钥失败，请重试'); }
  }
}

export const cloudKeyStore: CloudKeyStore = new CloudKeyStore();
