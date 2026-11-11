package io.github.jesterportfolio.hearth.phone;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** PhoneChat 하나가 소유하는 60초/4개/128KiB 캐시. 권한 검사 결과를 캐시하지 않습니다. */
public final class PhoneAnswerCache {
    private static final long TTL=60_000_000_000L;private static final int LIMIT=4,BYTES=128*1024;
    private record Entry(String answer,long created,int bytes){}
    public record Stats(int entries,int bytes,long hits,long misses){}
    private final LinkedHashMap<AssistantRequest,Entry> entries=new LinkedHashMap<>(4,.75f,true);private final LongSupplier clock;
    private Object scope;private int bytes;private long hits,misses;
    public PhoneAnswerCache(){this(System::nanoTime);}PhoneAnswerCache(LongSupplier clock){this.clock=clock;}
    public void select(Object next){if(!Objects.equals(scope,next)){invalidate();scope=next;}}
    public Optional<String> find(AssistantRequest request){prune();var item=entries.get(request);if(item==null){misses++;return Optional.empty();}hits++;return Optional.of(item.answer());}
    public void put(AssistantRequest request,String answer){
        prune();int size=request.prompt().getBytes(StandardCharsets.UTF_8).length+answer.getBytes(StandardCharsets.UTF_8).length;
        if(size>BYTES||answer.getBytes(StandardCharsets.UTF_8).length>8192)return;
        var prior=entries.remove(request);if(prior!=null)bytes-=prior.bytes();
        entries.put(request,new Entry(answer,clock.getAsLong(),size));bytes+=size;
        while(entries.size()>LIMIT||bytes>BYTES)removeFirst();
    }
    private void removeFirst(){var iterator=entries.entrySet().iterator();var entry=iterator.next();bytes-=entry.getValue().bytes();iterator.remove();}
    private void prune(){long now=clock.getAsLong();var iterator=entries.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();long age=now-entry.getValue().created();if(age<0||age>=TTL){bytes-=entry.getValue().bytes();iterator.remove();}}}
    public void invalidate(){entries.clear();bytes=0;scope=null;}
    public void clear(){invalidate();hits=misses=0;}
    public Stats stats(){prune();return new Stats(entries.size(),bytes,hits,misses);}
}
