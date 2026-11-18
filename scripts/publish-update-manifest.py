"""Sign a public update envelope and atomically advance its GitHub channel pointer.

Only public release metadata enters the channel. The private key is read from an
environment secret, written to a temporary 0600 file, and never printed.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_KEY = ROOT / 'app/src/main/res/raw/update_public_key.der'
KEY_ID = 'youshu-2026-11'
CHANNEL = 'update-channel'


def gh(*args, input=None):
    result = subprocess.run(['gh', *args], input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('GitHub operation failed; no channel was knowingly advanced.')
    return json.loads(result.stdout) if result.stdout.strip() else None


def openssl(*args):
    result = subprocess.run(['openssl', *map(str, args)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Update signing/verification failed.')
    return result.stdout


def payload(metadata, size, notes, now):
    version = metadata['version']
    assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', version)
    assert metadata['package'] == 'net.ankio.ledger' and metadata['abi'] == 'arm64-v8a' and metadata['signed'] is True
    assert type(metadata['versionCode']) is int and 0 < metadata['versionCode'] < 2**31
    assert re.fullmatch('[a-f0-9]{64}', metadata['sha256']) and 0 < size <= 256*1024*1024
    path = '/releases/v' + version + '/'
    return dict(packageName=metadata['package'], versionName=version, versionCode=metadata['versionCode'],
                abi=metadata['abi'], minSdk=30, apkPath=path+'app.apk', sourcePath=path+'source.zip',
                bytes=size, sha256=metadata['sha256'], issuedAt=now, expiresAt=now+30*86400, notes=notes[:12000])


def verify(envelope, directory):
    assert envelope['schemaVersion'] == 1 and envelope['algorithm'] == 'SHA256withRSA' and envelope['keyId'] == KEY_ID
    data = base64.b64decode(envelope['payload'], validate=True)
    signature = base64.b64decode(envelope['signature'], validate=True)
    (directory/'payload').write_bytes(data)
    (directory/'signature').write_bytes(signature)
    openssl('dgst', '-sha256', '-verify', PUBLIC_KEY, '-keyform', 'DER', '-signature', directory/'signature', directory/'payload')
    return json.loads(data)


def sign(value, directory):
    data = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
    (directory/'payload').write_bytes(data)
    secret = os.environ['UPDATE_MANIFEST_PRIVATE_KEY']
    key = directory/'private.pem'
    with key.open('wb') as stream:
        os.chmod(key, 0o600)
        stream.write(secret.encode())
    try:
        assert openssl('pkey', '-in', key, '-pubout', '-outform', 'DER') == PUBLIC_KEY.read_bytes(), 'Signing key does not match the app public key'
        signature = openssl('dgst', '-sha256', '-sign', key, directory/'payload')
    finally:
        key.unlink(missing_ok=True)
    envelope = dict(schemaVersion=1, algorithm='SHA256withRSA', keyId=KEY_ID,
                    payload=base64.b64encode(data).decode(), signature=base64.b64encode(signature).decode())
    assert verify(envelope, directory) == value
    return envelope


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-dir', type=Path)
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--renew', action='store_true')
    args = parser.parse_args()
    repository = os.environ['GITHUB_REPOSITORY']
    assert re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)
    api = f'repos/{repository}'
    refs = gh('api', f'{api}/git/matching-refs/heads/{CHANNEL}')
    ref = next((r for r in refs if r['ref'] == f'refs/heads/{CHANNEL}'), None)
    previous = None
    with tempfile.TemporaryDirectory(prefix='youshu-update-') as temp:
        directory = Path(temp)
        if ref:
            content = gh('api', f'{api}/contents/stable.json?ref={ref["object"]["sha"]}')
            previous = verify(json.loads(base64.b64decode(content['content'])), directory)
        now = int(datetime.datetime.now(datetime.timezone.utc).timestamp())
        if args.renew:
            if previous is None:
                print('No published update channel yet; renewal skipped.')
                return
            value = {**previous, 'issuedAt': now, 'expiresAt': now+30*86400}
        else:
            assert args.release_dir and args.notes
            metadata = json.loads((args.release_dir/'build-info.json').read_text())
            assert metadata['filename'] == f'Youshu-Ledger-{metadata["version"]}-arm64-v8a.apk'
            apk = args.release_dir/metadata['filename']
            assert hashlib.sha256(apk.read_bytes()).hexdigest() == metadata['sha256']
            assert (args.release_dir/'Youshu-Ledger-source.zip').is_file()
            value = payload(metadata, apk.stat().st_size, args.notes.read_text(encoding='utf-8'), now)
            if previous:
                assert value['versionCode'] > previous['versionCode'] or all(value[k] == previous[k] for k in ['versionCode','versionName','sha256','bytes','apkPath','sourcePath']), 'Refusing a downgrade or replacement of a published version'
        envelope = sign(value, directory)
        contents = json.dumps(envelope, separators=(',', ':'))+'\n'
        tree = gh('api', f'{api}/git/trees', '--method', 'POST', '--input', '-', input=json.dumps({'tree':[
            {'path':'stable.json','mode':'100644','type':'blob','content':contents}]}).encode())
        commit = gh('api', f'{api}/git/commits', '--method', 'POST', '--input', '-', input=json.dumps({
            'message':f'Publish signed update {value["versionName"]}', 'tree':tree['sha'],
            'parents':[ref['object']['sha']] if ref else []}).encode())
        # A non-fast-forward update fails if another publisher advanced the ref.
        if ref:
            gh('api', f'{api}/git/refs/heads/{CHANNEL}', '--method', 'PATCH', '--input', '-',
               input=json.dumps({'sha':commit['sha'],'force':False}).encode())
        else:
            gh('api', f'{api}/git/refs', '--method', 'POST', '--input', '-',
               input=json.dumps({'ref':f'refs/heads/{CHANNEL}','sha':commit['sha']}).encode())
        print(f'Published verified update metadata for version {value["versionCode"]}.')


if __name__ == '__main__':
    main()
