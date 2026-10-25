package io.github.jesterportfolio.hearth.inventory;

import io.github.jesterportfolio.hearth.persistence.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InventorySaveTest {
    private static InventoryPayload payload(int value) { byte[][] slots=new byte[41][]; slots[0]=new byte[]{(byte)value}; return InventoryPayload.pack(slots); }
    private static final class FakeStore implements InventoryStore {
        final List<CompletableFuture<Stored>> writes=new ArrayList<>();
        final List<InventoryPayload> payloads=new ArrayList<>();
        public CompletionStage<Stored> saveInventory(ServerProgressAccess.Context ctx,long revision,InventoryPayload payload) {
            payloads.add(payload); var future=new CompletableFuture<Stored>(); writes.add(future); return future;
        }
        public CompletionStage<Stored> createInventory(ServerProgressAccess.Context c,InventoryPayload p) { throw new UnsupportedOperationException(); }
        public CompletionStage<Stored> readInventory(ServerProgressAccess.Context c) { throw new UnsupportedOperationException(); }
    }
    private static final class Fixture {
        final Thread owner=Thread.currentThread(); final FakeStore store=new FakeStore(); final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final AtomicReference<InventoryPayload> memory=new AtomicReference<>(payload(1)); final AtomicBoolean valid=new AtomicBoolean(true);
        final List<String> errors=new ArrayList<>(); final InventorySaveSession session;
        Fixture() {
            var access=new ServerProgressAccess(); var ctx=access.forVerifiedServerLink(UUID.randomUUID(),UUID.randomUUID());
            session=new InventorySaveSession(store,ctx,0,memory::get,queue::add,() -> assertSame(owner,Thread.currentThread()),valid::get,errors::add);
        }
        void pump() { while (!queue.isEmpty()) queue.remove().run(); }
        void succeed(int index) { store.writes.get(index).complete(new InventoryStore.Stored(store.payloads.get(index),index+1)); pump(); }
    }
    @Test void envelopeRejectsCorruptionVersionsAndLengthsAndIsImmutable() throws Exception {
        var initial=payload(1); byte[] copy=initial.bytes(); copy[0]=0;
        assertEquals(initial,InventoryPayload.parse(initial.bytes())); assertNotEquals(copy[0],initial.bytes()[0]);
        assertThrows(IllegalArgumentException.class,() -> InventoryPayload.parse(copy));
        for (int position:new int[]{4,8,12}) {
            byte[] bad=initial.bytes(); ByteBuffer.wrap(bad).putInt(position,999);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bad,bad.length-32));
            System.arraycopy(hash,0,bad,bad.length-32,32);
            assertThrows(IllegalArgumentException.class,() -> InventoryPayload.parse(bad));
        }
        assertThrows(IllegalArgumentException.class,() -> InventoryPayload.pack(new byte[40][]));
        assertThrows(IllegalArgumentException.class,() -> InventoryPayload.parse(new byte[InventoryPayload.MAX_BYTES+1]));
        byte[][] extracted=initial.slots(); extracted[0][0]=55; assertEquals(1,initial.slots()[0][0]);
    }
    @Test void unchangedSkippedAndSaveCompletionOnlyCleansCapturedVersion() {
        var f=new Fixture(); f.session.flush(); assertTrue(f.store.writes.isEmpty());
        f.session.markDirty(); var first=f.session.flush(); assertSame(first,f.session.flush()); assertEquals(1,f.store.writes.size());
        f.memory.set(payload(2)); f.session.markDirty(); f.succeed(0);
        assertTrue(f.session.dirty()); assertEquals(1,f.session.savedVersion()); assertEquals(2,f.session.memoryVersion());
        assertEquals(1,f.session.revision()); f.session.flush(); assertEquals(payload(2),f.store.payloads.get(1));
        f.succeed(1); assertFalse(f.session.dirty()); assertEquals(2,f.session.revision());
    }
    @Test void failedAndStaleSavesPreserveDirtyAndDoNotAutoretry() {
        var f=new Fixture(); f.session.markDirty(); f.session.flush();
        f.store.writes.getFirst().completeExceptionally(new StorageFailure("test","23514")); f.pump();
        assertTrue(f.session.dirty()); assertEquals(0,f.session.revision()); f.session.flush(); assertEquals(1,f.store.writes.size());
        f.session.acknowledgeKnownRollback(); f.session.flush(); f.valid.set(false);
        f.store.writes.get(1).complete(new InventoryStore.Stored(f.store.payloads.get(1),1)); f.pump();
        assertTrue(f.session.dirty()); assertEquals(0,f.session.revision()); assertEquals("STALE_CONTEXT",f.session.holdCode());
        assertThrows(IllegalStateException.class,f.session::acknowledgeKnownRollback);
    }
    @Test void defaultThirtySecondClockSkipsCleanAndCoalescesPending() {
        var f=new Fixture(); var clock=new AtomicLong();
        var autosave=new InventoryAutosave(InventoryAutosave.DEFAULT_PERIOD,clock::get,() -> {}); autosave.add(f.session);
        assertEquals(Duration.ofSeconds(30),autosave.period()); clock.set(Duration.ofSeconds(30).toNanos());
        assertTrue(autosave.tick()); assertTrue(f.store.writes.isEmpty()); f.session.markDirty();
        clock.set(Duration.ofSeconds(59).toNanos()); assertFalse(autosave.tick());
        clock.set(Duration.ofSeconds(60).toNanos()); assertTrue(autosave.tick()); assertEquals(1,f.store.writes.size());
        clock.set(Duration.ofSeconds(90).toNanos()); autosave.tick(); assertEquals(1,f.store.writes.size());
        f.succeed(0); autosave.stop(); clock.set(Duration.ofSeconds(120).toNanos()); assertFalse(autosave.tick());
    }
}
