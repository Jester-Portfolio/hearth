package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AssistantDiagnosticsTest {
    @Test void playersAndInvalidArgumentsCannotEvenReadTheSnapshot(){
        var reads=new AtomicInteger();java.util.function.Supplier<MeasuredAssistant.Snapshot> source=()->{reads.incrementAndGet();throw new AssertionError();};
        assertTrue(AssistantDiagnostics.lines(false,new String[]{"status"},"ollama",source).getFirst().contains("콘솔"));
        for(String[] args:List.of(new String[0],new String[]{"reset"},new String[]{"status","extra"}))assertTrue(AssistantDiagnostics.lines(true,args,"ollama",source).getFirst().contains("사용법"));assertEquals(0,reads.get());
    }
    @Test void consoleReadsDoNotCallBackendOrExposeTextAndUnknownModesAreRejected(){
        var calls=new AtomicInteger();var service=new MeasuredAssistant(question->{calls.incrementAndGet();return CompletableFuture.completedFuture("private_answer");});
        service.ask("private_question");var before=service.snapshot();var lines=AssistantDiagnostics.lines(true,new String[]{"status"},"mock",service::snapshot);
        assertEquals(1,calls.get());assertEquals(before,service.snapshot());String text=String.join("\n",lines);assertTrue(text.contains("GENERATION started=1 success=1"));assertTrue(text.contains("CACHE_CONFIRMATION started=0"));assertFalse(text.contains("private_"));assertTrue(text.contains("판정하지"));
        assertThrows(IllegalArgumentException.class,()->AssistantDiagnostics.lines(true,new String[]{"status"},"http://secret",service::snapshot));assertTrue(lines.size()<=26);
    }
}
