package io.github.jesterportfolio.hearth.phone;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** 90초 준비 동작입니다. 실제 사용자 요청의 6초 제한은 변경하지 않습니다. */
public final class OllamaTrialWarmup {
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("http://127.0.0.1:11435/api/generate"))throw new IllegalArgumentException("TRIAL_ENDPOINT");
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
            var request=HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(85)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(OllamaProtocol.request("한국어로 준비 완료 라는 말만 답해."))).build();
            var response=http.send(request,info->new LocalAssistant.LimitedBody());Files.writeString(Path.of(args[1]),response.body());
            if(response.statusCode()!=200)throw new IllegalStateException("WARMUP_HTTP_STATUS");OllamaProtocol.answer(response.body());
            System.out.println("HEARTH_S5_WARMUP_PASS actual approved prompt/options");
        }
    }
}
