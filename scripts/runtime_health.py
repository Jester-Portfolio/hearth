#!/usr/bin/env python3
"""읽기 전용 Hearth 집계 로그 검사. 컨트롤러 관측이며 AI/DB 종단 간 수용 검사가 아닙니다."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import stat
import time
import uuid

PREFIX = re.compile(r'^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\]: \[Hearth\] HEARTH_RUNTIME_REPORT (.*)$')
FIELDS = {'format','boot','pid','observed_ms','mode','phase','controller_ready','worlds','held_worlds','ai_preparation_recorded','tps','mspt','heap_used_bytes'}
PHASES = {'STARTING','READY','DRAINING','DRAINED','HOLD','CLOSED','REFUSED'}
LIMIT = 1024 * 1024

class Unavailable(ValueError):
    pass

def regular(path):
    """고정 경로의 부모/최종 심볼릭 링크를 거부하고 실제 열린 파일 형식을 확인합니다."""
    path = Path(path).absolute()
    if path != path.resolve(strict=True):
        raise Unavailable('PATH_UNSAFE')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        os.close(fd)
        raise Unavailable('FILE_TYPE')
    return os.fdopen(fd, 'rb')

def nonce(path):
    with regular(path) as source:
        value = source.read(65)
    if len(value) > 64:
        raise Unavailable('BOOT_INVALID')
    text = value.decode('ascii').strip()
    if str(uuid.UUID(text)) != text:
        raise Unavailable('BOOT_INVALID')
    return text

def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Unavailable('REPORT_DUPLICATE_KEY')
        result[key] = value
    return result

def validate(row, boot, mode, now, max_age):
    if not isinstance(row, dict) or set(row) != FIELDS:
        raise Unavailable('REPORT_SCHEMA')
    for key in ('format','pid','observed_ms','worlds','held_worlds','heap_used_bytes'):
        if type(row[key]) is not int or row[key] < (1 if key in ('format','pid') else 0):
            raise Unavailable('REPORT_NUMBER')
    for key in ('controller_ready','ai_preparation_recorded'):
        if type(row[key]) is not bool:
            raise Unavailable('REPORT_BOOLEAN')
    for key in ('tps','mspt'):
        if type(row[key]) not in (int,float) or not math.isfinite(row[key]) or row[key] < 0:
            raise Unavailable('REPORT_NUMBER')
    if row['format'] != 1 or row['mode'] not in ('story','economy','story-prepare','lobby','survival') or row['phase'] not in PHASES:
        raise Unavailable('REPORT_SCHEMA')
    if row['boot'] != boot or row['mode'] != mode:
        raise Unavailable('BOOT_OR_MODE_MISMATCH')
    if row['held_worlds'] > row['worlds'] or row['controller_ready'] != (row['phase'] == 'READY' and row['held_worlds'] == 0):
        raise Unavailable('REPORT_INVARIANT')
    age = now - row['observed_ms'] / 1000
    if age < -5 or age > max_age:
        raise Unavailable('REPORT_STALE')
    return row

def inspect(log, boot_file, mode, max_age, now=None):
    if type(max_age) is not int or not 1 <= max_age <= 7200:
        raise Unavailable('MAX_AGE_INVALID')
    boot = nonce(boot_file)
    with regular(log) as source:
        size = os.fstat(source.fileno()).st_size
        start = max(0, size - LIMIT)
        source.seek(start)
        data = source.read(LIMIT)
    if start:
        data = data.partition(b'\n')[2]
    # 쓰기 중인 마지막 행은 이전의 완결 관측으로 처리합니다. 최신 완결 보고서가 잘못됐으면 실패합니다.
    lines = data.split(b'\n')[:-1]
    for line in reversed(lines):
        text = line.decode('utf-8',errors='replace')
        if re.match(r'^\[\d{2}:\d{2}:\d{2}\] \[Server thread/(?:INFO|WARN)\]: \[Hearth\] HEARTH_RUNTIME_(?:REPORT_UNAVAILABLE|OBSERVATION_REFUSED)',text):
            raise Unavailable('REPORT_UNAVAILABLE')
        match = PREFIX.fullmatch(text)
        if match:
            row = json.loads(match.group(1),object_pairs_hook=unique)
            return validate(row,boot,mode,time.time() if now is None else now,max_age)
    raise Unavailable('REPORT_MISSING')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log',required=True,type=Path)
    parser.add_argument('--boot-file',required=True,type=Path)
    parser.add_argument('--mode',required=True,choices=('story','economy','story-prepare','lobby','survival'))
    parser.add_argument('--max-age',required=True,type=int)
    args = parser.parse_args()
    try:
        row = inspect(args.log,args.boot_file,args.mode,args.max_age)
        print(json.dumps({'available':True,**row},separators=(',',':'),allow_nan=False))
        return 0 if row['controller_ready'] else 1
    except (OSError,ValueError,UnicodeError,TypeError,OverflowError) as error:
        print(json.dumps({'available':False,'code':str(error) if isinstance(error,Unavailable) else 'READ_OR_PARSE_FAILED'},separators=(',',':')))
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
