package io.github.jesterportfolio.hearth.phone;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LocalAssistantLifecycleTest {
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService workers=Executors.newFixedThreadPool(6);
        final CountDownLatch release=new CountDownLatch(1),entered;final List<String> bodies=Collections.synchronizedList(new ArrayList<>());
        Fixture(int waiting)throws Exception{
            entered=new CountDownLatch(waiting);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/ask",e->{
                var input=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);bodies.add(input);
                if(input.equals("wait")||input.endsWith("[현재 질문]\nwait")){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
                byte[] response=switch(input){case "invalid-utf8"->new byte[]{(byte)0xc3,0x28};case "large"->new byte[8193];case "blank"->" \n".getBytes(StandardCharsets.UTF_8);default->"정상 응답".getBytes(StandardCharsets.UTF_8);};
                e.getResponseHeaders().set("Content-Type",input.equals("html")?"text/html":input.equals("charset")?"text/plain; charset=iso-8859-1":"text/plain; charset=UTF-8");
                if(input.equals("redirect"))e.getResponseHeaders().set("Location","/must-not-follow");
                try{e.sendResponseHeaders(input.equals("redirect")?302:input.equals("status")?503:200,response.length);e.getResponseBody().write(response);}catch(java.io.IOException cancelled){}finally{e.close();}
            });
            server.createContext("/must-not-follow",e->{bodies.add("FORBIDDEN_REDIRECT");e.sendResponseHeaders(500,-1);e.close();});server.start();
        }
        String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/ask";}
        public void close(){release.countDown();server.stop(0);workers.shutdownNow();}
    }
    static AssistantFailure failure(CompletionStage<String> request)throws Exception{
        var error=assertThrows(ExecutionException.class,()->request.toCompletableFuture().get(2,TimeUnit.SECONDS));return assertInstanceOf(AssistantFailure.class,error.getCause());
    }
    @Test void fourRequestsAreBoundedAndCancellationImmediatelyFreesAdmission()throws Exception{
        try(var fixture=new Fixture(4);var service=new LocalAssistant(fixture.endpoint())){
            var pending=new ArrayList<CompletableFuture<String>>();for(int i=0;i<4;i++)pending.add(service.ask("wait").toCompletableFuture());
            assertTrue(fixture.entered.await(2,TimeUnit.SECONDS));assertEquals(AssistantFailure.Reason.BUSY,failure(service.ask("overflow")).reason());
            pending.getFirst().cancel(true);assertEquals("정상 응답",service.ask("next").toCompletableFuture().get(2,TimeUnit.SECONDS));
            assertFalse(fixture.bodies.contains("overflow"));service.close();for(var result:pending.subList(1,4))assertEquals(AssistantFailure.Reason.CLOSED,failure(result).reason());
            assertEquals(AssistantFailure.Reason.CLOSED,failure(service.ask("after close")).reason());
        }
    }
    @Test void timeoutReleasesCapacityAndLateResponseCannotChangeResult()throws Exception{
        try(var fixture=new Fixture(1);var service=new LocalAssistant(fixture.endpoint(),Duration.ofMillis(300))){
            assertEquals("정상 응답",service.ask("warmup").toCompletableFuture().get(2,TimeUnit.SECONDS));
            var old=service.ask("wait");assertTrue(fixture.entered.await(2,TimeUnit.SECONDS));assertEquals(AssistantFailure.Reason.TIMEOUT,failure(old).reason());
            assertEquals("정상 응답",service.ask("next").toCompletableFuture().get(2,TimeUnit.SECONDS));fixture.release.countDown();assertEquals(AssistantFailure.Reason.TIMEOUT,failure(old).reason());
        }
    }
    @Test void invalidResponsesAreRejectedWithoutRedirectOrRetry()throws Exception{
        try(var fixture=new Fixture(0);var service=new LocalAssistant(fixture.endpoint())){
            for(String question:List.of("invalid-utf8","large","blank","html","charset","status","redirect"))assertEquals(AssistantFailure.Reason.INVALID_RESPONSE,failure(service.ask(question)).reason(),question);
            assertEquals(7,fixture.bodies.size());assertFalse(fixture.bodies.contains("FORBIDDEN_REDIRECT"));assertEquals("정상 응답",service.ask("next").toCompletableFuture().get(2,TimeUnit.SECONDS));
        }
    }
    @Test void disconnectedEndpointIsUnavailableRatherThanMockSuccess()throws Exception{
        int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        try(var service=new LocalAssistant("http://127.0.0.1:"+port+"/ask")){assertEquals(AssistantFailure.Reason.UNAVAILABLE,failure(service.ask("질문")).reason());}
    }
    @Test void unknownModeCannotSilentlyBecomeMock(){
        for(String mode:List.of("LOCAL","locla","","production"))assertThrows(IllegalArgumentException.class,()->LocalAssistant.configured(mode,"http://127.0.0.1/ask"));
        try(var mock=LocalAssistant.configured("mock","unused")){assertTrue(mock.ask("질문").toCompletableFuture().join().startsWith("[Mock AI]"));}
    }
    @Test void leavingOneChatFreesSharedHttpCapacityForAnotherWithoutSharingHistory()throws Exception{
        try(var fixture=new Fixture(4);var service=new LocalAssistant(fixture.endpoint())){
            var chats=new ArrayList<PhoneChat>();var pending=new ArrayList<CompletableFuture<Void>>();
            try{
                for(int i=0;i<4;i++){var chat=new PhoneChat(service,Runnable::run);chats.add(chat);pending.add(chat.submit("wait","개인 기록 "+i,"diary",()->true).toCompletableFuture());}
                assertTrue(fixture.entered.await(2,TimeUnit.SECONDS));var next=new PhoneChat(service,Runnable::run);chats.add(next);
                assertThrows(ExecutionException.class,()->next.submit("본인 질문","다른 진행의 본인 기록","diary",()->true).toCompletableFuture().get(2,TimeUnit.SECONDS));
                assertTrue(next.error().contains("다른 요청"));assertTrue(next.history().isEmpty());chats.getFirst().close();assertTrue(pending.getFirst().isDone());
                next.submit("본인 질문","다른 진행의 본인 기록","diary",()->true).toCompletableFuture().get(2,TimeUnit.SECONDS);
                assertEquals(1,next.history().size());assertEquals(5,fixture.bodies.size());var body=fixture.bodies.stream().filter(s->s.contains("본인 질문")).findFirst().orElseThrow();
                assertTrue(body.contains("다른 진행의 본인 기록"));assertFalse(body.contains("개인 기록 "));assertFalse(body.contains("[이전 질문]"));
            }finally{chats.forEach(PhoneChat::close);}
        }
    }
}
