package io.github.jesterportfolio.hearth.features;

import java.util.*;
import java.util.function.*;

/** 같은 퀘스트 표시는 한 출력기만 소유합니다. 게임 진행이나 전투 채널은 건드리지 않습니다. */
public final class HudDelivery<P,V> implements AutoCloseable {
    private record Sink<P,V>(BiConsumer<P,V> show,Consumer<P> clear) {}
    private Sink<P,V> sink;
    private final Set<P> active=Collections.newSetFromMap(new IdentityHashMap<>());

    public HudDelivery(BiConsumer<P,V> show,Consumer<P> clear) {
        sink=new Sink<>(Objects.requireNonNull(show),Objects.requireNonNull(clear));
    }
    public void replace(BiConsumer<P,V> show,Consumer<P> clear) {
        var next=new Sink<P,V>(Objects.requireNonNull(show),Objects.requireNonNull(clear));
        close();
        sink=next;
    }
    public void show(P player,V view) {
        active.add(player);
        try {sink.show.accept(player,view);}
        catch(RuntimeException error) {
            try {clear(player);}catch(RuntimeException cleanup){error.addSuppressed(cleanup);}
            throw error;
        }
    }
    public void clear(P player) {
        if(active.contains(player)){sink.clear.accept(player);active.remove(player);}
    }
    public void close() {
        RuntimeException failure=null;
        for(var player:List.copyOf(active))try {clear(player);}catch(RuntimeException error) {
            if(failure==null)failure=error;else failure.addSuppressed(error);
        }
        if(failure!=null)throw failure;
    }
}
