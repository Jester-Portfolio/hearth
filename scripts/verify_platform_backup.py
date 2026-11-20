#!/usr/bin/env python3
"""통과·정지한 소유 격리 플랫폼의 전체 백업/복원. 서비스는 시작하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import time
import isolated_paths
import offline_backup


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('source','workspace','restored-source'):
        parser.add_argument('--'+key,type=Path,required=True)
    a=parser.parse_args()
    os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
    source=isolated_paths.workspace(a.source,'hearth-native-economy-run.')
    root=isolated_paths.workspace(a.workspace,'hearth-platform.')
    target=isolated_paths.workspace(a.restored_source,'hearth-native-economy-run.')
    if any(target.iterdir()):
        raise ValueError('FRESH_RESTORE_SOURCE_REQUIRED')
    prior=json.loads((source/'aura-trial-result.json').read_text())
    if prior['outcome']!='PASS' or prior['cleanup_errors'] or not prior.get('platform') or (source/'data/postmaster.pid').exists():
        raise ValueError('CLEAN_PASSED_PLATFORM_REQUIRED')
    started=time.monotonic()
    budget=offline_backup.Budget()
    assets=root/'backup-assets'
    assets.mkdir(mode=0o700)
    for name in ('paper.jar','rpg-expected.properties','platform-expected.properties','aura-trial-result.json'):
        shutil.copy2(source/name,assets/name)
    bundle=root/'platform-backup'
    receipt=offline_backup.create({'paper':source/'server','postgres':source/'data','assets':assets},bundle,offline_confirmed=True,budget=budget)
    manifest=offline_backup.verify(bundle,receipt['manifest_sha256'],budget=budget)
    paths={row['path'] for row in manifest['files']}
    required=['paper/plugins/Hearth/platform.properties','paper/plugins/Hearth/native-economy.properties','paper/plugins/Hearth/survival-worlds.properties','postgres/PG_VERSION']
    if not all(name in paths for name in required) or not any('dimensions/hearth_resource/' in name and name.endswith('/metadata.dat') for name in paths) or not any('/platform-changes/' in name and name.endswith('/manifest.properties') for name in paths):
        raise ValueError('COMPLETE_PLATFORM_BUNDLE_REQUIRED')
    restored=root/'platform-restored'
    restored_receipt=offline_backup.restore(bundle,restored,receipt['manifest_sha256'],budget=budget)
    shutil.copytree(restored/'paper',target/'server')
    shutil.copytree(restored/'postgres',target/'data')
    for path in (restored/'assets').iterdir():
        shutil.copy2(path,target/path.name)
    (target/'.hearth-native-economy-isolated').write_text('Hearth native economy isolated v1\n')
    (target/'.hearth-aura-isolated').write_text('Hearth AuraSkills isolated v1\n')
    result=dict(outcome='PASS',source=str(source),bundle=str(bundle),restored_source=str(target),receipt=receipt,restore=restored_receipt,duration_seconds=round(time.monotonic()-started,3),limits=dict(cpu=2,seconds=90,max_bytes=offline_backup.MAX_BYTES),services_started=False,operating_changes='NONE',new_JVM_acceptance='SEPARATE_PLATFORM_CONTEXT_TRIAL_REQUIRED')
    (target/'backup-origin.json').write_text(json.dumps(result,indent=2)+'\n')
    (root/'platform-backup-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('PLATFORM_BACKUP PASS',root/'platform-backup-result.json',flush=True)

if __name__=='__main__':
    main()
