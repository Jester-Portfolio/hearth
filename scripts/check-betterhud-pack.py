#!/usr/bin/env python3
"""생성된 BetterHud 팩의 Hearth 앵커·폰트·자산을 검사합니다. 실제 클라이언트 시험은 아닙니다."""
import argparse,hashlib,json,re,zipfile,struct,uuid
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack',type=Path,required=True)
    parser.add_argument('--betterhud-folder',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    assert not args.output.exists(), "기존 증거를 덮어쓰지 않는 새 출력 경로가 필요합니다"
    repo=Path(__file__).resolve().parents[1]
    checks=[]
    def check(value,label):
        assert value,label
        checks.append(label)
    with zipfile.ZipFile(args.pack) as pack:
        names=set(pack.namelist())
        digest=hashlib.sha256()
        for name in sorted((n for n in names if not n.endswith('/')),key=lambda n:n.encode('utf-16-be')):
            raw=name.encode('utf-8');data=pack.read(name)
            digest.update(struct.pack('>I',len(raw)));digest.update(raw);digest.update(struct.pack('>I',len(data)));digest.update(data)
        raw=bytearray(hashlib.md5(digest.digest()).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128
        pack_id=str(uuid.UUID(bytes=bytes(raw)))
        metadata=json.loads((args.betterhud_folder.parent/'Hearth/betterhud-pack.json').read_text())
        check(metadata['pack_id']==pack_id,'exported_pack_ID_matches_all_generated_assets')
        for relative in ['font/phone.json','items/phone.json','models/item/phone.json','textures/item/phone.png','textures/gui/frame.png']:
            key='assets/hearth/'+relative
            check(key in names and pack.read(key)==(repo/'resourcepack'/key).read_bytes(),'smartphone_asset_preserved_'+relative)
        pixel=[];hangul=0
        for name in sorted(names):
            if not name.endswith('.json') or '/font/' not in name:continue
            for provider in json.loads(pack.read(name)).get('providers',[]):
                chars=''.join(provider.get('chars',[]))
                if name.startswith('assets/betterhud/font/hud_hearth_minimap_') and '\ue100' in chars:pixel.append(provider)
                if name.startswith('assets/betterhud/font/hud_hearth_quest_') and '가' in chars:hangul+=1
        check(len(pixel)==16 and all(p['height']==4 for p in pixel),'sixteen_4px_pixel_font_rows')
        ascents=sorted(p['ascent'] for p in pixel)
        check(all(b-a==4 for a,b in zip(ascents,ascents[1:])),'minimap_row_offsets_are_4px')
        check(hangul>=12,'six_two_line_quest_fonts_include_Hangul')
        meta=json.loads(pack.read('pack.mcmeta'))
        check(meta['pack']['pack_format']==88,'target_pack_format_88')
        shader='betterhud_26_1/assets/minecraft/shaders/core/rendertype_text.vsh'
        text=pack.read(shader).decode()
        check('ScreenSize' in text and 'xGui = ui.x * 100.0 / 100.0' in text,'target_shader_has_viewport_relative_right_anchor')
    hud=json.loads((args.betterhud_folder/'huds/hearth-s4.yml').read_text())
    check(hud['hearth_quest']['layouts']['1']['gui']=={'x':0,'y':0},'quest_top_left_anchor')
    check(hud['hearth_minimap']['layouts']['1']['gui']=={'x':100,'y':0},'minimap_top_right_anchor')
    check(hud['hearth_minimap']['layouts']['1']['pixel']=={'x':-76,'y':12},'minimap_64px_with_12px_edge_margin')
    config=(args.betterhud_folder/'config.yml').read_text()
    for key in ['remove-default-hotbar','enable-self-host','merge-boss-bar']:
        check(re.search(r'^'+key+r':\s*false\s*$',config,re.M) is not None,key+'_disabled')
    result={'outcome':'PASS','pack':str(args.pack),'sha256':hashlib.sha256(args.pack.read_bytes()).hexdigest(),'checks':checks,'pack_id':pack_id,'client_rendering_tested':False}
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('BetterHud generated pack PASS:',len(checks),'checks')

if __name__=='__main__':main()
