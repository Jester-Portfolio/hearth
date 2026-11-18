#!/usr/bin/env python3
"""새 생존 backend의 3월드 설정만 작성합니다. 서버 기동·기존 데이터 변경은 하지 않습니다."""
import argparse
import os
from pathlib import Path
import uuid

def configuration(group,allow_create=False):
 group=str(uuid.UUID(str(group)))
 if type(allow_create) is not bool:raise ValueError('SURVIVAL_CONFIG_CREATE_FLAG')
 return f'''runtime-mode: survival
verification-enabled: false
runtime-observation-seconds: 5
network:
  enabled: false
survival-runtime:
  group-id: {group}
  allow-create: {str(allow_create).lower()}
  worlds:
    residence:
      key: minecraft:overworld
    wilderness:
      key: hearth_survival:wilderness
    nether:
      key: minecraft:the_nether
'''

def write(output,group,allow_create=False):
 output=Path(output).absolute()
 if output.parent!=output.parent.resolve(strict=True) or 'hearth-story-private' in output.parts:raise ValueError('SURVIVAL_CONFIG_PATH')
 text=configuration(group,allow_create)
 with os.fdopen(os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:stream.write(text)
 return output

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--group-id',default=None);p.add_argument('--allow-create',action='store_true');a=p.parse_args()
 try:write(a.output,a.group_id or uuid.uuid4(),a.allow_create);print('SURVIVAL_CONFIG_PREPARED; operating_applied=false')
 except (ValueError,OSError):p.exit(2,'SURVIVAL_CONFIG_REFUSED\n')
if __name__=='__main__':main()
