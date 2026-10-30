package io.github.jesterportfolio.hearth.util;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ShutdownCleanupTest {
    @Test void failureDoesNotSkipRemainingCleanupAndAllFailuresAreRetained(){
        var calls=new ArrayList<String>();var first=new IllegalStateException("UI");var second=new IllegalStateException("SAVE");
        var error=assertThrows(IllegalStateException.class,()->ShutdownCleanup.close(
            ()->{calls.add("ui");throw first;},null,()->{calls.add("save");throw second;},()->calls.add("pool")));
        assertEquals(List.of("ui","save","pool"),calls);assertSame(first,error.getCause());assertArrayEquals(new Throwable[]{second},error.getSuppressed());
    }
    @Test void successfulCleanupPreservesOrder(){
        var calls=new ArrayList<Integer>();assertDoesNotThrow(()->ShutdownCleanup.close(()->calls.add(1),null,()->calls.add(2)));assertEquals(List.of(1,2),calls);
    }
}
