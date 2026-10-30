#!/usr/bin/env python3
"""스마트폰 리소스팩의 JSON·PNG·참조를 검사하고 배포 ZIP을 만듭니다. 서버 설정은 변경하지 않습니다."""
import hashlib,json,struct,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
pack=root/'resourcepack'
for p in pack.rglob('*.json'):json.loads(p.read_text())
meta=json.loads((pack/'pack.mcmeta').read_text());assert meta['pack']['min_format']==[88,0]
font=json.loads((pack/'assets/hearth/font/phone.json').read_text())
for provider in font['providers']:
    texture=pack/'assets/hearth/textures'/provider['file'].split(':')[1]
    data=texture.read_bytes();assert data.startswith(b'\x89PNG\r\n\x1a\n');w,h=struct.unpack('>II',data[16:24]);assert w>0 and h>0 and provider['ascent']<=provider['height']
assert len({glyph for p in font['providers'] for row in p['chars'] for glyph in row})==7
for p in pack.rglob('*.png'):assert p.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
output=root/'build/hearth-phone-pack.zip';output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(pack.rglob('*')):
        if p.is_file() and p.suffix!='.md':
            entry=zipfile.ZipInfo(p.relative_to(pack).as_posix(),(2026,10,30,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;z.writestr(entry,p.read_bytes())
print('리소스팩 JSON·PNG·Glyph 검사 PASS · ZIP:',output,'· SHA-1:',hashlib.sha1(output.read_bytes()).hexdigest())
