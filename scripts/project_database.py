#!/usr/bin/env python3
"""비밀 없는 Hearth 전용 PG 프로필 검사 및 Compose 환경 준비. 서비스/DB/비밀 파일을 열지 않는다."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re

CONNECTION_KEYS = {'db-host', 'db-port', 'db-name', 'db-user', 'password-environment'}
DEPLOYMENT_KEYS = {'project', 'image', 'base-image', 'uid', 'gid', 'data', 'review', 'service-root',
                   'password-file', 'backup-directory', 'restart', 'resource-limits-selected'}


def require(condition):
    if not condition:
        raise ValueError('PROJECT_DATABASE_PROFILE')


def unique_pairs(pairs):
    values = {}
    for key, value in pairs:
        require(key not in values)
        values[key] = value
    return values


def validate(profile):
    require(isinstance(profile, dict) and set(profile) == {'format', 'id', 'postgres-version', 'connection', 'deployment'})
    require(type(profile['format']) is int and profile['format'] == 1 and profile['id'] == 'hearth-economy-postgres')
    require(isinstance(profile['postgres-version'], str) and re.fullmatch(r'[1-9][0-9]*\.[0-9]+', profile['postgres-version']))
    c, d = profile['connection'], profile['deployment']
    require(isinstance(c, dict) and set(c) == CONNECTION_KEYS and isinstance(d, dict) and set(d) == DEPLOYMENT_KEYS)
    require(c['db-host'] == '127.0.0.1' and type(c['db-port']) is int and 1024 <= c['db-port'] <= 65535)
    for key in ('db-name', 'db-user'):
        require(isinstance(c[key], str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', c[key]))
    require(c['password-environment'] == 'HEARTH_DB_PASSWORD')
    require(d['project'] == 'hearth-project-db')
    require(isinstance(d['image'], str) and re.fullmatch(r'sha256:[a-f0-9]{64}', d['image']))
    require(isinstance(d['base-image'], str) and re.fullmatch(r'postgres@sha256:[a-f0-9]{64}', d['base-image']))
    require(all(type(d[key]) is int and d[key] > 0 for key in ('uid', 'gid')))
    require(d['restart'] == 'no' and d['resource-limits-selected'] is False)
    for key in ('service-root', 'data', 'review', 'password-file', 'backup-directory'):
        value = d[key]
        require(isinstance(value, str) and re.fullmatch(r'/[A-Za-z0-9_./-]+', value))
        path = PurePosixPath(value)
        require(str(path) == value and '..' not in path.parts and 'hearth-story-private' not in path.parts)
    root = PurePosixPath(d['service-root'])
    require(len(root.parts) >= 3)
    for key, suffix in {'data':'data', 'review':'review', 'password-file':'secrets/app.password', 'backup-directory':'backups'}.items():
        require(PurePosixPath(d[key]) == root / suffix)
    return profile


def load(path):
    path = Path(path).absolute()
    require(path == path.resolve(strict=True) and path.is_file() and path.stat().st_size <= 16384)
    return validate(json.loads(path.read_text(), object_pairs_hook=unique_pairs))


def compose_environment(profile):
    validate(profile)
    c, d = profile['connection'], profile['deployment']
    # Approval is supplied by the existing private operating record, never inferred from Git.
    values = {'HEARTH_PG_PROJECT':d['project'], 'HEARTH_PG_IMAGE':d['image'], 'HEARTH_PG_UID':d['uid'],
              'HEARTH_PG_GID':d['gid'], 'HEARTH_PG_PORT':c['db-port'], 'HEARTH_PG_DATA':d['data'],
              'HEARTH_PG_REVIEW':d['review']}
    return ''.join(key + '=' + str(value) + '\n' for key, value in values.items())


def write_environment(profile, output):
    text = compose_environment(profile)
    output = Path(output).absolute()
    require(output.parent == output.parent.resolve(strict=True) and 'hearth-story-private' not in output.parts)
    with os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--output-env', type=Path)
    args = parser.parse_args()
    try:
        profile = load(args.profile)
        if args.output_env:
            write_environment(profile, args.output_env)
        print('PROJECT_DATABASE_PROFILE PASS; service_changes=false; secrets_read=false')
    except (ValueError, OSError, TypeError):
        parser.exit(2, 'PROJECT_DATABASE_PROFILE_REFUSED\n')


if __name__ == '__main__':
    main()
