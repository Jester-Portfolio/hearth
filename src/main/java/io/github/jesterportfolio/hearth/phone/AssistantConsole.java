package io.github.jesterportfolio.hearth.phone;

import java.util.concurrent.Executor;
import java.util.function.*;

/** 콘솔의 명시적 준비만 실행합니다. status는 계속 조회 전용이며 원문/주소를 출력하지 않습니다. */
public final class AssistantConsole {
    private AssistantConsole(){}
    public static void execute(boolean console,String[] args,String mode,MeasuredAssistant service,Executor owner,BooleanSupplier active,Consumer<String> reply){
        if(!console){reply.accept("AI 관리 진단과 준비는 서버 콘솔 전용입니다.");return;}
        if(args.length==1&&args[0].equals("status")){AssistantDiagnostics.lines(true,args,mode,service::snapshot).forEach(reply);return;}
        if(args.length!=1||!args[0].equals("prepare")){reply.accept("사용법: hearthai status | prepare");return;}
        if(!mode.equals("ollama")){reply.accept("명시적 모델 준비는 ollama 모드에서만 지원합니다.");return;}
        if(!active.getAsBoolean()){reply.accept("AI 연결이 종료되어 준비하지 않습니다.");return;}
        reply.accept("고정 AI 모델 준비를 요청합니다. 최대 90초이며 준비 중에는 질문을 보내지 않습니다.");
        service.prepare().whenCompleteAsync((value,error)->{if(!active.getAsBoolean())return;reply.accept(error==null?"고정 AI 모델 적재와 초기 연산 준비를 확인했습니다. 사용자 질문 제한과 60초 keep-alive는 유지됩니다.":AssistantFailure.message(error));},owner);
    }
}
