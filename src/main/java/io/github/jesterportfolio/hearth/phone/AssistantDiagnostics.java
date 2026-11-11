package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.function.Supplier;

/** 콘솔에서 집계만 읽습니다. 조회로 HTTP/예열/초기화/게임 상태 변경을 실행하지 않습니다. */
public final class AssistantDiagnostics {
    private AssistantDiagnostics(){}
    public static List<String> lines(boolean console,String[] args,String mode,Supplier<MeasuredAssistant.Snapshot> snapshot){
        if(!console)return List.of("AI 관리 진단은 서버 콘솔 전용입니다.");
        if(args.length!=1||!args[0].equals("status"))return List.of("사용법: hearthai status");
        if(!Set.of("mock","local","ollama").contains(mode))throw new IllegalArgumentException("UNKNOWN_PHONE_AI_MODE");
        var state=snapshot.get();var lines=new ArrayList<String>();lines.add("AI 백엔드 호출 집계 mode="+mode+" closed="+state.closed());
        lines.add("서버 메모리 누계 · 최신 연결 가능/모델 준비 상태와 PG·표시 성공 여부는 판정하지 않습니다.");
        for(var row:state.usage()){
            long finished=row.succeeded()+row.failed()+row.cancelled();
            lines.add(row.kind()+" started="+row.started()+" success="+row.succeeded()+" failed="+row.failed()+" cancelled="+row.cancelled()+" active="+row.active()+" avg_ms="+(finished==0?0:row.totalMillis()/finished)+" max_ms="+row.maxMillis());
            lines.add(row.kind()+" latency_ms[<=100,<=500,<=1000,<=3000,<=6000,>6000]="+row.latencyBuckets());
            for(var reason:AssistantFailure.Reason.values())if(row.failures().containsKey(reason))lines.add(row.kind()+" failure="+reason+" count="+row.failures().get(reason));
        }
        return List.copyOf(lines);
    }
}
