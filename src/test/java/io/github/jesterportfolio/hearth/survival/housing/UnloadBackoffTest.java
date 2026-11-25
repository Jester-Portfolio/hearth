package io.github.jesterportfolio.hearth.survival.housing;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class UnloadBackoffTest{
 @Test void failuresWaitThenHoldOnlyTheirWorldUntilExplicitRetry(){var retry=new UnloadBackoff();UUID a=UUID.randomUUID(),b=UUID.randomUUID();assertTrue(retry.allowed(a,0));retry.fail(a,0);assertFalse(retry.allowed(a,4999));assertTrue(retry.allowed(b,0));assertTrue(retry.allowed(a,5000));retry.fail(a,5000);assertFalse(retry.allowed(a,14999));retry.fail(a,15000);assertFalse(retry.allowed(a,Long.MAX_VALUE));assertTrue(retry.snapshot().get(a).held());retry.clear(a);assertTrue(retry.allowed(a,15000));}
}
