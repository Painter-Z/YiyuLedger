"""Prepare a reviewable GitHub source tree and release assets; never upload.

Keep signing configuration and credentials local. Historical installers retain
their original filenames and bytes. GitHub authentication is not used here.
"""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.7.0'
EXCLUDED_PARTS = {'.git', '.venv', '.tooling', '__pycache__', '.pytest_cache', '.hvigor', '.gradle', '.kotlin',
                  'oh_modules', 'node_modules', 'build', '.idea', 'captures'}
EXCLUDED_SUFFIXES = {'.pyc', '.p12', '.p7b', '.cer', '.jks', '.keystore', '.pem', '.key', '.hap', '.app',
                     '.apk', '.aab', '.log', '.db', '.sqlite', '.sqlite3', '.wav', '.pcm', '.mp3', '.m4a'}
SOURCE_ROOTS = ['android', 'harmony', 'shared', 'backend', 'preview', 'tests', 'tools', 'docs']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def local_secret_values():
    """Load only into memory for comparison; never return values to terminal."""
    result = []
    config = ROOT / 'backend/.env'
    if config.exists():
        result += [value.strip('"\'').encode() for value in re.findall(r'^DASHSCOPE_API_KEY=(\S+)', config.read_text(encoding='utf-8-sig'), re.M)]
    token = ROOT / 'backend/.access-token'
    if token.exists() and len(token.read_bytes().strip()) > 15:
        result.append(token.read_bytes().strip())
    return result


def audit_bytes(data, label, secrets, depth=0):
    if any(secret and secret in data for secret in secrets):
        raise ValueError(f'Local credential detected in {label}; value suppressed')
    if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data):
        raise ValueError(f'Private key detected in {label}; value suppressed')
    # Production key formats; documented synthetic test placeholders are safe.
    if re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-ws-[A-Za-z0-9_.-]{24,})', data):
        raise ValueError(f'Credential-shaped value detected in {label}; value suppressed')
    if depth < 2 and data[:4] == b'PK\x03\x04':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if archive.testzip() is not None:
                raise ValueError(f'ZIP integrity failed: {label}')
            for item in archive.infolist():
                if not item.is_dir():
                    audit_bytes(archive.read(item), f'{label}!{item.filename}', secrets, depth + 1)


def copy_source(repository):
    for name in ['.gitignore', 'package.json']:
        shutil.copy2(ROOT / name, repository / name)
    for name in SOURCE_ROOTS:
        for directory, subdirs, files in os.walk(ROOT / name):
            subdirs[:] = [d for d in subdirs if d not in EXCLUDED_PARTS and not (Path(directory) / d).is_symlink()]
            for name in sorted(files):
                source = Path(directory) / name
                if source.is_symlink() or (name.startswith('.env') and name != '.env.example'):
                    continue
                if name in {'.access-token', 'local.properties'} or source.suffix.lower() in EXCLUDED_SUFFIXES:
                    continue
                relative = source.relative_to(ROOT)
                destination = repository / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                if relative.as_posix() == 'harmony/build-profile.json5':
                    profile = json.loads(source.read_text(encoding='utf-8-sig'))
                    profile['app']['signingConfigs'] = []
                    for product in profile['app']['products']:
                        product.pop('signingConfig', None)
                    write_json(destination, profile)
                else:
                    shutil.copy2(source, destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--name', default='YiyuLedger')
    parser.add_argument('--output', default='artifacts/github-upload-20261018')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9-]+', args.owner) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', args.name):
        parser.error('Use a GitHub owner and repository name, never credentials')
    output = (ROOT / args.output).resolve()
    if output == ROOT or ROOT not in output.parents or 'artifacts' not in output.relative_to(ROOT).parts:
        parser.error('Output must be a new folder under this project artifacts directory')
    output.mkdir(parents=True, exist_ok=False)
    repository = output / 'repository'
    repository.mkdir()
    copy_source(repository)
    repo_url = f'https://github.com/{args.owner}/{args.name}'
    paragraph = ('一语账是一款支持原生鸿蒙与安卓的 AI 语音记账应用。按住语音键说出消费或收入，即可自动识别名称、金额、分类和日期，'
                 '支持一句记录多笔、手动及文字输入、账单搜索与编辑、周／月／年收支图表、月预算提醒，以及 CSV 导出和 JSON 备份恢复。'
                 '填写自己的百炼 API Key 后即可联网识别，日常使用无需连接电脑，账本保存在本地。')
    readme = ('# 一语账\n\n' + paragraph + '\n\n## 下载 0.7.0\n\n'
              f'- [鸿蒙版 0.7.0（HAP，调试签名包）]({repo_url}/releases/download/v0.7.0/YiyuLedger-0.7.0-signed.hap)\n'
              f'- [安卓版 0.7.0（APK）]({repo_url}/releases/download/v0.7.0/YiyuLedger-0.7.0-android-debug.apk)\n')
    (repository / 'README.md').write_text(readme, encoding='utf-8', newline='\n')
    # Record the cross-platform state accurately in the upload copy.
    architecture = repository / 'docs/ARCHITECTURE.md'
    text = architecture.read_text(encoding='utf-8')
    text = text.replace('当前鸿蒙0.7.0和安卓0.6.3', '当前鸿蒙0.7.0和安卓0.7.0')
    text = text.replace('安卓及未接入的后端原型继续使用其现有协议，详见[识别修复](HARMONY-0.7.0-RECOGNITION.md)。',
                        '安卓0.7.0已同步逐笔来源协议及复核逻辑；未接入的后端原型继续使用旧协议。详见[鸿蒙识别修复](HARMONY-0.7.0-RECOGNITION.md)及[安卓同步说明](ANDROID-0.7.0.md)。')
    architecture.write_text(text, encoding='utf-8', newline='\n')
    secrets = local_secret_values()
    for file in repository.rglob('*'):
        if file.is_file():
            audit_bytes(file.read_bytes(), file.relative_to(output).as_posix(), secrets)
    packages = []
    history_tag = 'history-20261018'
    for source in sorted((ROOT / 'artifacts').iterdir()):
        match = re.fullmatch(r'YiyuLedger-(\d+\.\d+\.\d+)-(signed|unsigned|android-debug)\.(hap|apk)', source.name)
        if not match:
            continue
        version, build, extension = match.groups()
        tag = f'v{VERSION}' if version == VERSION else history_tag
        destination = output / 'release-assets' / tag / source.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        audit_bytes(source.read_bytes(), source.name, secrets)
        shutil.copy2(source, destination)
        assert sha(source) == sha(destination)
        packages.append(dict(version=version, platform='harmony' if extension == 'hap' else 'android',
            build=build, filename=source.name, bytes=source.stat().st_size, sha256=sha(source),
            release_tag=tag, download_url=f'{repo_url}/releases/download/{tag}/{source.name}'))
    assert len(packages) == 19, 'Historical package count changed; review inventory before uploading'
    assert len([p for p in packages if p['release_tag'] == f'v{VERSION}']) == 2
    metadata = dict(application='一语账', repository_url=repo_url, intended_visibility='private', latest_version=VERSION,
        prepared_at=datetime.now(timezone(timedelta(hours=8))).isoformat(),
        packages=packages, package_count=len(packages), harmony_count=sum(p['platform']=='harmony' for p in packages),
        android_count=sum(p['platform']=='android' for p in packages), total_installer_bytes=sum(p['bytes'] for p in packages))
    write_json(repository / 'releases/manifest.json', metadata)
    lines = ['# 安装包历史', '', '按现有归档文件保留原文件名及SHA-256摘要。最新源码为双端0.7.0；历史安装包单独归档。', '',
             '| 版本 | 系统 | 安装包 |', '| --- | --- | --- |']
    for item in sorted(packages, key=lambda p: (tuple(map(int,p['version'].split('.'))),p['platform']), reverse=True):
        note = '（早期未签名构建，仅留档）' if item['build'] == 'unsigned' else ''
        lines.append(f"| {item['version']} | {'鸿蒙' if item['platform']=='harmony' else '安卓'} | [{item['filename']}]({item['download_url']}){note} |")
    (repository / 'releases/HISTORY.md').write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    for tag in [history_tag, f'v{VERSION}']:
        folder = output / 'release-assets' / tag
        selected = [p for p in packages if p['release_tag']==tag]
        (folder / 'SHA256SUMS.txt').write_text(''.join(f"{p['sha256']}  {p['filename']}\n" for p in selected), encoding='utf-8', newline='\n')
    history_body = ('鸿蒙与安卓的历史安装包归档，覆盖0.1.0至0.6.3，共17个文件。保留原始文件及摘要；各系统版本并非一一对应。'
                    '\n\n0.1.0 HAP为早期未签名构建，仅留档。其他鸿蒙HAP是个人调试签名包，需要匹配签名设备。历史包可能依赖当时的调试环境。'
                    '\n\n此归档的Git标签指向本次归档提交，旧版本对应的安装包以附件为准。SHA256SUMS.txt可用于校验。\n')
    latest_body = ('一语账0.7.0，鸿蒙与安卓同步优化中文口语金额、多笔账单对应及逐笔确认进度。支持“十一块八毛九”等表达，异常提案最多自动复核一次。'
                   '\n\n附件为个人测试版：安卓APK可覆盖同签名旧版；鸿蒙HAP需要匹配调试签名设备。升级不要先卸载。语音识别使用用户自己的百炼API Key，安装包不内置密钥。'
                   '\n\nSHA256SUMS.txt可用于核对文件。\n')
    (output / 'release-notes').mkdir()
    (output / f'release-notes/{history_tag}.md').write_text(history_body, encoding='utf-8', newline='\n')
    (output / f'release-notes/v{VERSION}.md').write_text(latest_body, encoding='utf-8', newline='\n')
    source_zip = output / f'YiyuLedger-{VERSION}-github-source.zip'
    with zipfile.ZipFile(source_zip, 'x', zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(repository.rglob('*')):
            if file.is_file(): archive.write(file, 'YiyuLedger/' + file.relative_to(repository).as_posix())
    source_files = [p for p in repository.rglob('*') if p.is_file()]
    metadata.update(source_files=len(source_files), source_sha256={p.relative_to(repository).as_posix():sha(p) for p in source_files},
        source_zip=dict(filename=source_zip.name, bytes=source_zip.stat().st_size, sha256=sha(source_zip)),
        credential_audit='passed', signature_materials_excluded=True, local_signing_profile_sanitized=True,
        repository_created=False, code_uploaded=False, packages_uploaded=False, readme_review='pending')
    write_json(output / 'upload-manifest.json', metadata)
    plan = f'''# GitHub 上传材料

目标仓库：{repo_url}，先设为私有。README待用户审阅，当前不上传源码或附件。

- `repository/`：双端0.7.0最新源码，共{len(source_files)}个文件，根README为本次审阅稿。
- `release-assets/v0.7.0/`：最新鸿蒙HAP、安卓APK及校验文件。
- `release-assets/{history_tag}/`：其余17个历史包及校验文件。
- `release-notes/`：两组Release的简短发布说明。
- `{source_zip.name}`：与repository内容一致的源码备份。
- `upload-manifest.json`：所有源码、安装包的SHA-256及拟上传位置。

19个安装包合计{metadata['total_installer_bytes']:,}字节，约{metadata['total_installer_bytes']/1024/1024:.2f}MiB。鸿蒙12个、安卓7个，覆盖14个版本。0.1.0为未签名历史构建。

API Key、后端连接口令、签名密钥与证书、本地SDK配置、编译目录、模拟器文件、原始测试日志、录音和截图均不在上传源码中。鸿蒙build-profile.json5仅复制不含签名信息的配置，原开发工程不改动。所有安装包按原始字节归档。

审阅通过后：提交repository到main → 创建历史归档Release（不设为最新版）并上传17个旧包 → 创建v0.7.0 Release并上传2个新包 → 核验下载文件与清单摘要一致。下载链接会在附件上传完成后生效。当前只准备材料，不添加开源许可证。

GitHub Releases说明：https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
'''
    (output / 'UPLOAD-PLAN.md').write_text(plan, encoding='utf-8', newline='\n')
    print(f'Prepared {output}')
    print(f'Source: {len(source_files)} files; installers: 12 Harmony + 7 Android; SHA-256 and credential audits passed.')
    print('README review pending. No GitHub upload performed.')


if __name__ == '__main__':
    main()
