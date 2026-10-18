// Keep restool and its bundled DLL dependencies together in a private build directory.
// This avoids Windows DLL search failures without editing the installed IDE or SDK.
const path = require('node:path');
const fs = require('node:fs');
if (process.platform === 'win32' && process.env.YIYU_RESOURCE_RUNTIME) {
  const replacement = path.join(process.env.YIYU_RESOURCE_RUNTIME, 'restool.exe');
  if (!fs.existsSync(replacement)) throw new Error('Missing prepared Harmony resource compiler');
  const childProcess = require('node:child_process');
  for (const method of ['spawn', 'spawnSync']) {
    const original = childProcess[method];
    childProcess[method] = function (command, ...args) {
      return original.call(this, path.basename(command).toLowerCase() === 'restool.exe' ? replacement : command, ...args);
    };
  }
}
