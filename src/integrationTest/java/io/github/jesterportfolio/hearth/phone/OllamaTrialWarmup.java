package io.github.jesterportfolio.hearth.phone;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** 90초 준비 동작입니다. 실제 사용자 요청의 6초 제한은 변경하지 않습니다. */
public final class OllamaTrialWarmup {
    public static void main(String[] args)throws Exception{
        if((args.length!=2&&!(args.length==3&&args[2].equals("grounded")))||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
            var grounding=new AssistantRequest("상점 이름과 자료 번호만 답하세요.",java.util.List.of(),"[자료 1] 공개 상점 기록\n상점의 이름은 느티상점이다. 이 자료는 현재 진행에서 공개된 기록이다.\n"+"공개된 물품 목록은 기록에 없으므로 추측하지 않는다. ".repeat(5),1);
            var request=HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(85)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(OllamaProtocol.request(args.length==3?grounding.prompt():"한국어로 준비 완료 라는 말만 답해."))).build();
            var response=http.send(request,info->new LocalAssistant.LimitedBody());Files.writeString(Path.of(args[1]),response.body());
            if(response.statusCode()!=200)throw new IllegalStateException("WARMUP_HTTP_STATUS");var answer=OllamaProtocol.answer(response.body());if(args.length==3)AssistantAnswer.present(grounding,answer);
            System.out.println("HEARTH_S5_WARMUP_PASS actual approved prompt/options");
        }
    }
}
