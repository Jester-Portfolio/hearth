"""정상 새 진행 준비 검사의 공개 입력만 새 소유 경로에 만듭니다."""
import hashlib
import json
import shutil
import textwrap
from pathlib import Path

from isolated_paths import workspace


def spatial_hash(folder):
    digest=hashlib.sha256()
    files=sorted(p for name in ('region','entities','poi') if (folder/name).exists() for p in (folder/name).rglob('*') if p.is_file())
    assert files and all(not p.is_symlink() for p in files)
    for path in files:
        digest.update(path.relative_to(folder).as_posix().encode());digest.update(b'\0')
        with path.open('rb') as stream:
            for chunk in iter(lambda:stream.read(65536),b''):digest.update(chunk)
    return digest.hexdigest()


def configure(root,plugins,source):
    source=workspace(source,'hearth-s4-11.')
    record=json.loads((source/'run-result.json').read_text())
    assert record['outcome']=='PASS' and record['all_owned_processes_and_ports_closed'],'완료한 비활성 증거만 템플릿 입력으로 복사합니다'
    original=source/'server/world/dimensions/hearth_verify/restart_template'
    folder=plugins/'Hearth';template=folder/'templates/native_template'
    template.mkdir(parents=True,mode=0o700)
    for name in ('region','entities','poi'):
        if (original/name).exists():shutil.copytree(original/name,template/name,symlinks=True)
    regions=source/'server/plugins/WorldGuard/worlds/hearth_verify_restart_a/regions.yml'
    assert not regions.is_symlink()
    shutil.copyfile(regions,template/'worldguard-regions.yml')
    (template/'data/paper').mkdir(parents=True)
    shutil.copyfile(original/'data/paper/metadata.dat',template/'data/paper/metadata.dat')
    mapping=dict(line.split('=',1) for line in (source/'server/plugins/Hearth/restart-mapping.properties').read_text().splitlines() if line and not line.startswith('#'))
    (template/'source-uuid.txt').write_text(mapping['restart_template.uuid']+'\n')
    (template/'playerdata').mkdir();(template/'playerdata/source-only.dat').write_text('synthetic personal sentinel; never clone\n')
    shape=spatial_hash(template)
    assert shape==mapping['templateDigest'],'원본 공간 해시와 새 읽기 전용 복사본이 다릅니다'
    protection=hashlib.sha256((template/'worldguard-regions.yml').read_bytes()).hexdigest()
    resources=Path(__file__).resolve().parents[1]/'src/main/resources'
    contents='\n'.join(section+':\n'+textwrap.indent((resources/name).read_text(),'  ') for section,name in [('quests','quest-demo.yml'),('exploration','exploration-demo.yml'),('shared','story-demo.yml'),('rules','quest-rules-demo.yml'),('scenes','cutscene-demo.yml')])
    (folder/'story-preparation-content.yml').write_text(contents)
    endpoint=dict(line.split('=',1) for line in (root/'db-endpoint.properties').read_text().splitlines() if '=' in line)
    lines=['verification-enabled: false','runtime-mode: story-prepare','story-preparation:','  content-file: story-preparation-content.yml','  content-sha256: '+hashlib.sha256(contents.encode()).hexdigest(),'  max-template-files: 10000','  max-template-bytes: 1073741824','  reserve-bytes: 67108864','  db-host: 127.0.0.1','  db-port: '+endpoint['port'],'  db-name: hearth_s4_11','  db-user: postgres','  password-environment: HEARTH_ISOLATED_STORY_PASSWORD','  jobs:']
    for label,number in [('a',1001),('b',1002)]:
        lines+=['    - id: '+label,'      run: 00000000-0000-002a-0000-'+format(number,'012x'),'      world-key: hearth_story:prepared_'+label,'      template: native_template','      template-version: native-template-v1','      template-sha256: '+shape,'      regions-sha256: '+protection,'      entry-x: 0.5','      entry-y: 65','      entry-z: 0.5','      entry-yaw: 0','      entry-pitch: 0','      required-regions: [protected]']
    (folder/'config.yml').write_text('\n'.join(lines)+'\n')
