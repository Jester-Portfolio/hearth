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
            assertEquals("응답: 시험",service.ask("시험").toCompletableFuture().get());assertThrows(java.util.concurrent.ExecutionException.class,()->service.ask("large").toCompletableFuture().get());
        }finally{server.stop(0);}
    }
}
