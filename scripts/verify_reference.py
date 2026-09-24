#!/usr/bin/env python3
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'docs/android-source.json').read_text())
errors=[]
for item in manifest['files']:
    path=root/'android-reference'/item['path']
    if not path.is_file(): errors.append(item['path']+': missing');continue
    data=path.read_bytes()
    actual=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    if actual!=item['sha']: errors.append(item['path']+': hash mismatch')
if errors: raise SystemExit('\n'.join(errors))
print(f"Verified {len(manifest['files'])} original files byte-for-byte at {manifest['commit']}")
