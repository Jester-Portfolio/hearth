package io.github.jesterportfolio.hearth.phone;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LocalAssistantTest {
    @Test void rejectsRemoteOrCredentialBearingEndpoints(){for(String s:new String[]{"https://127.0.0.1/ask","http://example.com/ask","http://user:pass@127.0.0.1/ask"})assertThrows(IllegalArgumentException.class,()->new LocalAssistant(s));}
    @Test void isolatedHttpProtocolAndSizeFailure()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ask",e->{var input=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);byte[] out=(input.equals("large")?"x".repeat(9000):"응답: "+input).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,out.length);try(var stream=e.getResponseBody()){stream.write(out);}});server.start();
        try(var service=new LocalAssistant("http://127.0.0.1:"+server.getAddress().getPort()+"/ask")){
            assertEquals("응답: 시험",service.ask("시험").toCompletableFuture().get());
            var request=new AssistantRequest("도움",java.util.List.of(),"허용 정보");assertEquals("응답: "+request.prompt(),service.ask(request).toCompletableFuture().get());assertThrows(java.util.concurrent.ExecutionException.class,()->service.ask("large").toCompletableFuture().get());
        }finally{server.stop(0);}
    }
    @Test void cancellingDelayedHttpRequestDoesNotBlockNextRequest()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=java.util.concurrent.Executors.newFixedThreadPool(2);server.setExecutor(workers);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/ask",e->{String input=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(input.equals("wait")){entered.countDown();try{release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
            try{byte[] bytes="정상 응답".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}catch(java.io.IOException cancelled){}finally{e.close();}});server.start();
        try(var service=new LocalAssistant("http://127.0.0.1:"+server.getAddress().getPort()+"/ask")){
            var old=service.ask("wait").toCompletableFuture();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(old.cancel(true));
            assertEquals("정상 응답",service.ask("next").toCompletableFuture().get(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(old.isCancelled());
        }finally{release.countDown();server.stop(0);workers.shutdownNow();}
    }

}
