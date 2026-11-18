#!/usr/bin/env python3
"""새 private 디렉터리에 로비/경제 네트워크 설정을 준비합니다. 기존 서버/데이터/네트워크는 수정하지 않습니다."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import uuid

ENV_FORWARD='HEARTH_VELOCITY_SECRET'
ENV_RELAY='HEARTH_NETWORK_SECRET'

def section(text,key,value):
    pattern=r'(?m)^'+re.escape(key)+r':[^\n]*\n(?:[ \t][^\n]*\n|\n)*'
    return re.sub(pattern,'',text).rstrip()+'\n'+value.rstrip()+'\n'

def safe(path):
    path=Path(path).absolute()
    if 'hearth-story-private' in path.parts or path!=path.resolve(strict=True):
        raise ValueError('NETWORK_CONFIG_PATH')
    return path

def generate(output,network,ports,lobby,economy,max_players):
    output=Path(output).absolute()
    safe(output.parent)
    if output.exists() or output.is_symlink() or 'hearth-story-private' in output.parts:
        raise ValueError('NETWORK_CONFIG_NEW_OUTPUT_REQUIRED')
    network=str(uuid.UUID(str(network)))
    if set(ports)!=set(('proxy','lobby','economy')) or len(set(ports.values()))!=3 or any(type(v) is not int or not 1024<=v<=65535 for v in ports.values()):
        raise ValueError('NETWORK_CONFIG_PORTS')
    if type(max_players) is not int or not 1<=max_players<=4096:raise ValueError('NETWORK_CONFIG_PLAYERS')
    if not re.search(r'^runtime-mode: economy\s*$',economy,re.M) or re.search(r'^[a-z-]*verification-enabled: true\s*$',economy,re.M):raise ValueError('NETWORK_ECONOMY_NORMAL_CONFIG_REQUIRED')
    if len(economy.encode())>1024*1024:raise ValueError('NETWORK_CONFIG_SIZE')
    world=str(uuid.UUID(lobby['world-uuid']));world_key=lobby['world-key'];region=lobby['region']
    if not re.fullmatch(r'[a-z0-9_]+:[a-z0-9_]+',world_key) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',region):raise ValueError('NETWORK_LOBBY_CONFIG')
    for k in ('entry-x','entry-y','entry-z','entry-yaw','entry-pitch'):
        if type(lobby[k]) not in (int,float) or not math.isfinite(lobby[k]):raise ValueError('NETWORK_LOBBY_CONFIG')
    if abs(lobby['entry-x'])>29999000 or abs(lobby['entry-z'])>29999000 or abs(lobby['entry-pitch'])>90:raise ValueError('NETWORK_LOBBY_CONFIG')
    forward=secrets.token_hex(32);relay=secrets.token_hex(32)
    common=f'  enabled: true\n  network-id: {network}\n  lobby: lobby\n  economy: economy\n  forwarding-secret-environment: {ENV_FORWARD}\n  relay-secret-environment: {ENV_RELAY}\n'
    files={
      'proxy/velocity.toml':f'''config-version = "2.9"
bind = "127.0.0.1:{ports['proxy']}"
motd = "Hearth Network"
show-max-players = {max_players}
online-mode = true
force-key-authentication = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
lobby = "127.0.0.1:{ports['lobby']}"
economy = "127.0.0.1:{ports['economy']}"
try = ["lobby"]
[forced-hosts]
[advanced]
bungee-plugin-message-channel = false
failover-on-unexpected-server-disconnect = false
haproxy-protocol = false
accepts-transfers = false
[query]
enabled = false
''',
      'proxy/forwarding.secret':forward+'\n',
      'proxy/plugins/hearth-network/network.properties':f'network-id={network}\nserver-id=lobby\nlobby=lobby\neconomy=economy\nforwarding-secret-environment={ENV_FORWARD}\nrelay-secret-environment={ENV_RELAY}\n',
      'secrets/forwarding.secret':forward+'\n',
      'secrets/relay.secret':relay+'\n',
      'paper-global.fragment.yml':f'proxies:\n  bungee-cord:\n    online-mode: true\n  proxy-protocol: false\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: {forward}\n',
      'economy/plugins/Hearth/config.yml':section(economy,'network','network:\n'+common+'  server-id: economy\n'),
      'lobby/plugins/Hearth/config.yml':'runtime-mode: lobby\nverification-enabled: false\nruntime-observation-seconds: 5\nnetwork:\n'+common+'  server-id: lobby\nlobby-runtime:\n  world-key: '+world_key+'\n  world-uuid: '+world+'\n  region: '+region+'\n'+''.join('  '+k+': '+str(lobby[k])+'\n' for k in ('entry-x','entry-y','entry-z','entry-yaw','entry-pitch')),
    }
    for role in ('lobby','economy'):
        files[role+'/server.properties.fragment']=f'server-ip=127.0.0.1\nserver-port={ports[role]}\nonline-mode=false\nwhite-list=true\nenforce-whitelist=true\nenable-rcon=false\nenable-query=false\nmax-players={max_players}\n'
    output.mkdir(mode=0o700)
    hashes={}
    for name,value in files.items():
        path=output/name;path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        with path.open('x') as stream:stream.write(value)
        path.chmod(0o600);hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest()
    manifest=dict(format=1,network_id=network,ports=ports,role_routes={'lobby':'economy','economy':'lobby'},files=hashes,operating_applied=False)
    (output/'network-bundle.json').write_text(json.dumps(manifest,indent=2)+'\n');(output/'network-bundle.json').chmod(0o600)
    return manifest

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--economy-config',type=Path,required=True);p.add_argument('--network-id',default=None)
    for role in ('proxy','lobby','economy'):p.add_argument('--'+role+'-port',type=int,required=True)
    p.add_argument('--max-players',type=int,required=True);p.add_argument('--lobby-world-uuid',required=True);p.add_argument('--lobby-world-key',default='minecraft:overworld');p.add_argument('--lobby-region',required=True)
    for c in ('x','y','z','yaw','pitch'):p.add_argument('--lobby-'+c,type=float,required=True)
    a=p.parse_args()
    try:
        source=safe(a.economy_config)
        if not source.is_file() or source.stat().st_size>1024*1024:raise ValueError()
        lobby={'world-uuid':a.lobby_world_uuid,'world-key':a.lobby_world_key,'region':a.lobby_region,**{'entry-'+c:getattr(a,'lobby_'+c) for c in ('x','y','z','yaw','pitch')}}
        generate(a.output,a.network_id or uuid.uuid4(),{r:getattr(a,r+'_port') for r in ('proxy','lobby','economy')},lobby,source.read_text(),a.max_players)
        print('NETWORK_CONFIG_PREPARED; existing services/data unchanged')
    except (OSError,ValueError,KeyError,TypeError):p.exit(2,'NETWORK_CONFIG_REFUSED\n')

if __name__=='__main__':main()
