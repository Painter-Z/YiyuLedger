from datetime import datetime
from pathlib import Path
import json
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / 'artifacts' / ('YiyuLedger-source-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.zip')
output.parent.mkdir(exist_ok=True)
allowed_roots = {'harmony', 'android', 'shared', 'backend', 'preview', 'tests', 'tools', 'docs'}
allowed_files = {'README.md', 'package.json', '.gitignore'}
excluded_parts = {'.venv', '.tooling', '__pycache__', '.pytest_cache', '.hvigor', '.gradle', '.kotlin', 'oh_modules', 'build', '.idea', '.git', 'captures'}
excluded_suffixes = {'.pyc', '.p12', '.p7b', '.cer', '.jks', '.keystore', '.hap', '.app', '.apk', '.aab', '.log'}
count = 0
with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
    for file in root.rglob('*'):
        relative = file.relative_to(root)
        if relative.parts[0] not in allowed_roots and relative.as_posix() not in allowed_files:
            continue
        if any(part in excluded_parts for part in relative.parts) or not file.is_file():
            continue
        if file.name.startswith('.env') and file.name != '.env.example':
            continue
        if file.name in {'.access-token', 'local.properties'} or file.suffix in excluded_suffixes:
            continue
        target = 'YiyuLedger/' + relative.as_posix()
        if relative.as_posix() == 'harmony/build-profile.json5':
            # The IDE may add local signing passwords; never distribute that configuration.
            profile = json.loads(file.read_text(encoding='utf-8-sig'))
            profile['app']['signingConfigs'] = []
            for product in profile['app']['products']:
                product.pop('signingConfig', None)
            archive.writestr(target, json.dumps(profile, ensure_ascii=False, indent=2) + '\n')
        else:
            archive.write(file, target)
        count += 1
print(f'Source archive: {output} ({count} files). No environment files, tokens, SDK caches, or signing materials included.')
