import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = new URL('../harmony/entry/src/main/ets/model/Ledger.ts', import.meta.url);
await mkdir(new URL('../preview/assets/', import.meta.url), { recursive: true });
await writeFile(new URL('../preview/ledger-core.js', import.meta.url), stripTypeScriptTypes(await readFile(source, 'utf8'), { mode: 'strip' }));
console.log('Preview uses the same ledger calculations as the Harmony client.');
