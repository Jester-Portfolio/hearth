#!/usr/bin/env python3
"""기존 집계 관측만 읽는 bounded 지속 관측. 접속자/거래/게임 효과를 합성하지 않는다."""
import argparse,json,math,time
from pathlib import Path
import runtime_health

def observe(log,boot,output,duration,interval,max_age,min_tps,max_mspt,max_heap_mib,clock=time.monotonic,sleep=time.sleep):
    if type(duration) is not int or not 1<=duration<=550 or type(interval) is not int or not 1<=interval<=60 or any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in (min_tps,max_mspt,max_heap_mib)):
        raise ValueError('SOAK_LIMIT')
    output=Path(output).absolute()
    if output.exists() or output.is_symlink() or output.parent!=output.parent.resolve(strict=True):raise ValueError('SOAK_FRESH_OUTPUT')
    start=clock();count=0;bad=0;last=None;max_heap=0;worst_mspt=0;lowest_tps=None
    with output.open('x') as out:
        while clock()-start<duration:
            try:
                row=runtime_health.inspect(log,boot,'survival',max_age)
                fresh=row['observed_ms']!=last
                last=row['observed_ms'];heap=row['heap_used_bytes']/1048576
                ok=row['controller_ready'] and row['tps']>=min_tps and row['mspt']<=max_mspt and heap<=max_heap_mib
                max_heap=max(max_heap,heap);worst_mspt=max(worst_mspt,row['mspt']);lowest_tps=row['tps'] if lowest_tps is None else min(lowest_tps,row['tps']);count+=int(fresh)
                item={'ok':ok,'report':row}
            except (OSError,ValueError,TypeError,OverflowError):
                ok=False;item={'ok':False,'code':'OBSERVATION_UNAVAILABLE'}
            if not ok:bad+=1
            out.write(json.dumps(item,separators=(',',':'),allow_nan=False)+'\n');out.flush()
            remaining=duration-(clock()-start)
            if remaining>0:sleep(min(interval,remaining))
    return {'format':1,'outcome':'PASS' if count>0 and bad==0 else 'FAIL','samples':count,'failed_samples':bad,'duration_seconds':clock()-start,'max_heap_mib':max_heap,'max_mspt':worst_mspt,'min_tps':lowest_tps,'actual_client':'NOT_RUN','workload':'existing real activity only; no generated load'}
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ('log','boot-file','output'):p.add_argument('--'+n,required=True,type=Path)
    for n in ('duration','interval','max-age'):p.add_argument('--'+n,required=True,type=int)
    for n in ('min-tps','max-mspt','max-heap-mib'):p.add_argument('--'+n,required=True,type=float)
    a=p.parse_args()
    try:r=observe(a.log,a.boot_file,a.output,a.duration,a.interval,a.max_age,a.min_tps,a.max_mspt,a.max_heap_mib);print(json.dumps(r,separators=(',',':')));return 0 if r['outcome']=='PASS' else 1
    except (OSError,ValueError,TypeError):print('SOAK_REFUSED');return 2
if __name__=='__main__':raise SystemExit(main())
