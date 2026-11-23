#!/usr/bin/env python3
"""Baut das System-Update-Manifest: Version aus jeder Datei, Größe, SHA-256, Adresse. Signatur macht der Aufrufer (openssl)."""
import hashlib, json, os, shutil, sys, datetime
src, out, base = sys.argv[1], sys.argv[2], sys.argv[3].rstrip('/')
os.makedirs(out, exist_ok=True)
comps = json.load(open(os.path.join(src, 'components.json'), encoding='utf-8'))
entries = []
for c in comps:
    p = os.path.join(src, c['file'])
    data = open(p, 'rb').read()
    ver = json.loads(data.decode('utf-8'))['version']
    shutil.copyfile(p, os.path.join(out, c['file']))
    entries.append({'id': c['id'], 'name': c['name'], 'version': ver, 'url': f"{base}/{c['file']}",
                    'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'changes': c.get('changes', ''),
                    'priority': c.get('priority', 'normal'), 'minAppVersion': c.get('minAppVersion', 0)})
m = {'schema': 1, 'generated': datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'), 'components': entries}
open(os.path.join(out, 'system-manifest.json'), 'w', encoding='utf-8').write(json.dumps(m, ensure_ascii=False, indent=2))
print(json.dumps(m, ensure_ascii=False, indent=2))
