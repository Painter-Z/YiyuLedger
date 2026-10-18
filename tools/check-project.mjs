import { readFile, readdir, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceRoot = path.join(root, 'harmony');
async function files(dir) {
  const result = [];
  for (const item of await readdir(dir, { withFileTypes: true })) {
    if (['.hvigor', 'oh_modules', 'build'].includes(item.name)) continue;
    const target = path.join(dir, item.name);
    if (item.isDirectory()) result.push(...await files(target)); else result.push(target);
  }
  return result;
}
const entries = await files(sourceRoot);
const media = new Set(entries.filter(file => /[/\\]media[/\\]/.test(file)).map(file => path.basename(file, path.extname(file))));
const resources = new Set();
let errors = [];
for (const file of entries.filter(file => /[/\\]element[/\\].*\.json$/.test(file))) {
  const json = JSON.parse(await readFile(file, 'utf8'));
  for (const [kind, values] of Object.entries(json)) for (const value of values) resources.add(kind + ':' + value.name);
}
for (const file of entries) {
  if (!/\.(json5?|ets|ts)$/.test(file)) continue;
  const text = await readFile(file, 'utf8');
  if (/\.json5?$/.test(file)) {
    try { JSON.parse(text); } catch { errors.push(`${path.relative(root,file)}: invalid JSON configuration`); }
    if (path.basename(file) === 'layered_image.json') {
      const layers = JSON.parse(text)['layered-image'];
      if (!layers?.background || !layers?.foreground) errors.push('Invalid layered app icon configuration');
    }
  }
  for (const match of text.matchAll(/(?:app\.media\.|\$media:)([a-zA-Z0-9_]+)/g)) {
    if (!media.has(match[1])) errors.push(`${path.relative(root,file)}: missing media ${match[1]}`);
  }
  for (const match of text.matchAll(/\$(string|color):([a-zA-Z0-9_]+)/g)) {
    if (!resources.has(match[1] + ':' + match[2])) errors.push(`${path.relative(root,file)}: missing ${match[0]}`);
  }
  for (const match of text.matchAll(/from ['"](\.\.[^'"]+|\.\/[^'"]+)['"]/g)) {
    const base = path.resolve(path.dirname(file), match[1]);
    const exists = await Promise.all(['', '.ts', '.ets'].map(async ext => {
      try { await access(base+ext); return true; } catch { return false; }
    }));
    if (!exists.some(Boolean)) errors.push(`${path.relative(root,file)}: missing import ${match[1]}`);
  }
}
const module = JSON.parse(await readFile(path.join(sourceRoot, 'entry/src/main/module.json5'), 'utf8')).module;
for (const ability of module.abilities) await access(path.join(sourceRoot, 'entry/src/main', ability.srcEntry));
const pages = JSON.parse(await readFile(path.join(sourceRoot, 'entry/src/main/resources/base/profile/main_pages.json'), 'utf8'));
for (const page of pages.src) await access(path.join(sourceRoot, 'entry/src/main/ets', page + '.ets'));
for (const permission of ['ohos.permission.INTERNET', 'ohos.permission.MICROPHONE']) {
  if (!module.requestPermissions.some(item => item.name === permission)) errors.push('Missing permission '+permission);
}
if (errors.length) { errors.forEach(error => console.error(error)); process.exitCode = 1; }
else console.log(`Project references verified: ${entries.length} files, ${media.size} media resources. This is not an ArkTS SDK compile.`);
