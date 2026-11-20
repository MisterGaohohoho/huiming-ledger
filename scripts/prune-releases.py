"""Keep the release referenced by the verified stable channel; preserve Git tags."""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

SPEC = importlib.util.spec_from_file_location('update_publisher', Path(__file__).with_name('publish-update-manifest.py'))
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


def gh(*args):
    result = subprocess.run(['gh', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('GitHub release operation failed; cleanup stopped.')
    return json.loads(result.stdout) if result.stdout.strip() else None


def pointer(repository):
    latest = gh('api', f'repos/{repository}/releases/latest')
    content = gh('api', f'repos/{repository}/contents/stable.json?ref=update-channel')
    envelope = json.loads(base64.b64decode(content['content'], validate=False))
    with tempfile.TemporaryDirectory(prefix='youshu-release-cleanup-') as temp:
        channel = PUBLISHER.verify(envelope, Path(temp))
    if not channel['issuedAt'] - 300 <= time.time() < channel['expiresAt']:
        raise RuntimeError('The stable update channel is not current; cleanup stopped.')
    return latest, channel


def older_releases(releases, latest, channel, keep_tag):
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', keep_tag):
        raise ValueError('Invalid release tag')
    if latest.get('tag_name') != keep_tag or latest.get('draft', True) or latest.get('prerelease', True):
        raise RuntimeError('The requested release is not the latest published stable release.')
    if type(latest.get('id')) is not int or latest['id'] <= 0:
        raise RuntimeError('Invalid latest release identity')
    version = keep_tag[1:]
    if (channel.get('versionName') != version or channel.get('packageName') != 'net.ankio.ledger'
            or channel.get('abi') != 'arm64-v8a'
            or channel.get('apkPath') != f'/releases/{keep_tag}/app.apk'
            or channel.get('sourcePath') != f'/releases/{keep_tag}/source.zip'):
        raise RuntimeError('The stable updater does not reference the release being kept.')
    apk = f'Youshu-Ledger-{version}-arm64-v8a.apk'
    assets = {asset['name']: asset for asset in latest.get('assets', [])}
    required = {apk, apk + '.sha256', 'build-info.json', 'Youshu-Ledger-source.zip', 'Youshu-Ledger-source.sha256'}
    if not required <= assets.keys() or any(assets[name].get('size', 0) <= 0 for name in required):
        raise RuntimeError('The retained release is missing required assets.')
    if assets[apk]['size'] != channel.get('bytes'):
        raise RuntimeError('The retained APK size does not match the signed channel.')
    kept = [item for item in releases if item.get('id') == latest['id'] and item.get('tag_name') == keep_tag]
    if len(kept) != 1:
        raise RuntimeError('The retained release is missing or ambiguous in the listing.')
    targets = []
    for item in releases:
        if item.get('draft', True) or item.get('id') == latest['id']:
            continue
        if type(item.get('id')) is not int or item['id'] <= 0 or item.get('tag_name') == keep_tag:
            raise RuntimeError('Invalid older release identity')
        targets.append({'id': item['id'], 'tag_name': item['tag_name']})
    return targets


def prune(repository, keep_tag, apply=False):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    pages = gh('api', '--paginate', '--slurp', f'repos/{repository}/releases')
    releases = [item for page in pages for item in page]
    latest, channel = pointer(repository)
    targets = older_releases(releases, latest, channel, keep_tag)
    print(json.dumps({'keep': keep_tag, 'delete': targets, 'apply': apply}), flush=True)
    if not apply:
        return
    for target in targets:
        current, value = pointer(repository)
        older_releases([current], current, value, keep_tag)
        if current['id'] != latest['id']:
            raise RuntimeError('The stable release changed during cleanup.')
        route = f'repos/{repository}/releases/{target["id"]}'
        candidate = gh('api', route)
        if candidate.get('tag_name') != target['tag_name'] or candidate.get('draft', True):
            raise RuntimeError('An older release changed during cleanup.')
        gh('api', route, '--method', 'DELETE')
        print(f'Deleted release {target["tag_name"]}; Git tag retained.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--keep-tag', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not args.repository:
        parser.error('--repository is required outside GitHub Actions')
    prune(args.repository, args.keep_tag, args.apply)
