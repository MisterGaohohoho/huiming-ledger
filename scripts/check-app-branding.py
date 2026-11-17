"""Fail builds if the previous product name survives in runtime sources or an APK."""
import argparse
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
old = '\u82b1\u6709\u8c31'
parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path)
args = parser.parse_args()
found = []
if args.apk:
    with zipfile.ZipFile(args.apk) as archive:
        for name in archive.namelist():
            if name.endswith('.dex') or name == 'resources.arsc' or name.startswith(('assets/web/', 'res/')):
                data = archive.read(name)
                if old.encode() in data or old.encode('utf-16-le') in data:
                    found.append(name)
else:
    for folder in ('app/src/main', 'core/src/main'):
        for path in (root / folder).rglob('*'):
            if path.is_file() and path.suffix in {'.kt', '.java', '.xml', '.html', '.js', '.css', '.json', '.txt'}:
                if old in path.read_text(encoding='utf-8', errors='replace'):
                    found.append(str(path.relative_to(root)))
if found:
    raise SystemExit('Previous product name found: ' + ', '.join(found))
print('PASS: application branding' + (' in APK including DEX' if args.apk else ' in runtime sources'))
