#!/usr/bin/env python3
"""새 생존 backend의 3월드 설정만 작성합니다. 서버 기동·기존 데이터 변경은 하지 않습니다."""
import argparse
import json
import os
import re
from pathlib import Path
import uuid

def configuration(group,allow_create=False,economy=None):
 group=str(uuid.UUID(str(group)))
 if type(allow_create) is not bool:raise ValueError('SURVIVAL_CONFIG_CREATE_FLAG')
 text=f'''runtime-mode: survival
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

 if economy is not None:
  required={'realm','content-file','db-host','db-port','db-name','db-user','password-environment','allow-new-realm'}
  if set(economy)!=required:raise ValueError('SURVIVAL_ECONOMY_CONFIG')
  uuid.UUID(str(economy['realm']))
  if type(economy['allow-new-realm']) is not bool or type(economy['db-port']) is not int or not 1<=economy['db-port']<=65535:raise ValueError('SURVIVAL_ECONOMY_CONFIG')
  for key,pattern in {'content-file':r'[A-Za-z0-9_-]+\.properties','db-host':r'[A-Za-z0-9.-]{1,253}','db-name':r'[A-Za-z_][A-Za-z0-9_]{0,62}','db-user':r'[A-Za-z_][A-Za-z0-9_]{0,62}','password-environment':r'[A-Z][A-Z0-9_]{0,63}'}.items():
   if not isinstance(economy[key],str) or not re.fullmatch(pattern,economy[key]):raise ValueError('SURVIVAL_ECONOMY_CONFIG')
  text+='survival-economy:\n  enabled: true\n'+''.join('  '+key+': '+(str(value).lower() if type(value) is bool else str(value) if type(value) is int else json.dumps(str(value),ensure_ascii=False))+'\n' for key,value in economy.items())
 return text

def write(output,group,allow_create=False,economy=None):
 output=Path(output).absolute()
 if output.parent!=output.parent.resolve(strict=True) or 'hearth-story-private' in output.parts:raise ValueError('SURVIVAL_CONFIG_PATH')
 text=configuration(group,allow_create,economy)
 with os.fdopen(os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:stream.write(text)
 return output

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--group-id',default=None);p.add_argument('--allow-create',action='store_true');p.add_argument('--with-economy',action='store_true');p.add_argument('--economy-realm');p.add_argument('--allow-new-realm',action='store_true');p.add_argument('--content-file',default='survival-economy.properties');p.add_argument('--db-host',default='127.0.0.1');p.add_argument('--db-port',type=int,default=5432);p.add_argument('--db-name',default='hearth_economy');p.add_argument('--db-user',default='hearth');p.add_argument('--password-environment',default='HEARTH_DB_PASSWORD');a=p.parse_args()
 try:
  if not a.with_economy and (a.allow_new_realm or a.economy_realm):raise ValueError('SURVIVAL_ECONOMY_REQUIRED')
  economy=None
  if a.with_economy:economy={'realm':a.economy_realm or str(uuid.uuid4()),'allow-new-realm':a.allow_new_realm,**{key:getattr(a,key.replace('-','_')) for key in ('content-file','db-host','db-port','db-name','db-user','password-environment')}}
  write(a.output,a.group_id or uuid.uuid4(),a.allow_create,economy);print('SURVIVAL_CONFIG_PREPARED; operating_applied=false')
 except (ValueError,OSError):p.exit(2,'SURVIVAL_CONFIG_REFUSED\n')
if __name__=='__main__':main()
