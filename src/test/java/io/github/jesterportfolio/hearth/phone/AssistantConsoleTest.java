package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AssistantConsoleTest {
    @Test void onlyExactConsoleOllamaPreparationCanCallTheBackend(){
        var calls=new AtomicInteger();var backend=new AssistantService(){public CompletionStage<String> ask(String q){throw new AssertionError();}public CompletionStage<String> prepare(){calls.incrementAndGet();return CompletableFuture.completedFuture("private_server_payload");}};var measured=new MeasuredAssistant(backend);var replies=new ArrayList<String>();
        AssistantConsole.execute(false,new String[]{"prepare"},"ollama",measured,Runnable::run,()->true,replies::add);
        AssistantConsole.execute(true,new String[]{"prepare","extra"},"ollama",measured,Runnable::run,()->true,replies::add);
        AssistantConsole.execute(true,new String[]{"prepare"},"mock",measured,Runnable::run,()->true,replies::add);
        AssistantConsole.execute(true,new String[]{"prepare"},"ollama",measured,Runnable::run,()->false,replies::add);assertEquals(0,calls.get());
        AssistantConsole.execute(true,new String[]{"status"},"ollama",measured,Runnable::run,()->true,replies::add);assertEquals(0,calls.get());
        AssistantConsole.execute(true,new String[]{"prepare"},"ollama",measured,Runnable::run,()->true,replies::add);assertEquals(1,calls.get());assertTrue(replies.getLast().contains("적재"));assertFalse(String.join("\n",replies).contains("private_"));
    }
    @Test void failureTextIsBoundedAndShutdownSuppressesLateConsoleResults(){
        for(boolean shutdown:List.of(false,true)){
            var pending=new CompletableFuture<String>();var active=new AtomicBoolean(true);var replies=new ArrayList<String>();var backend=new AssistantService(){public CompletionStage<String> ask(String q){throw new AssertionError();}public CompletionStage<String> prepare(){return pending;}};
            try(var service=new MeasuredAssistant(backend)){AssistantConsole.execute(true,new String[]{"prepare"},"ollama",service,Runnable::run,active::get,replies::add);if(shutdown)active.set(false);pending.completeExceptionally(new IllegalStateException("private_endpoint_token"));assertEquals(shutdown?1:2,replies.size());assertFalse(String.join("\n",replies).contains("private_"));}
        }
    }
}
