import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { iconPng } from './icon-png.mjs';
const source = await readFile(new URL('../preview/app.js', import.meta.url), 'utf8');
const paths = [...source.matchAll(/^  (\w+): '([^']+)',?$/gm)];
const nativeDir = new URL('../harmony/entry/src/main/resources/base/media/', import.meta.url);
const appDir = new URL('../harmony/AppScope/resources/base/media/', import.meta.url);
const previewDir = new URL('../preview/assets/', import.meta.url);
for(const dir of [nativeDir,appDir,previewDir])await mkdir(dir,{recursive:true});
for(const [,name,path] of paths){
  const svg=`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#234236" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;
  await writeFile(new URL(`ic_${name}.svg`,nativeDir),svg);
}
const logo='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="28" fill="#234236"/><g fill="none" stroke="#E0EDCC" stroke-width="6.5" stroke-linecap="round"><path d="M31 45v10M43 30v40M55 36v28M67 43v14"/></g></svg>';
await writeFile(new URL('app_icon.svg',previewDir),logo);
for(const dir of [nativeDir,appDir]){
  await writeFile(new URL('app_icon.png',dir),iconPng());
  await writeFile(new URL('icon_background.png',dir),iconPng('background'));
  await writeFile(new URL('icon_foreground.png',dir),iconPng('foreground'));
  await writeFile(new URL('layered_image.json',dir),JSON.stringify({'layered-image':{background:'$media:icon_background',foreground:'$media:icon_foreground'}},null,2));
}
console.log(`Created ${paths.length} vector icons and the app mark.`);
