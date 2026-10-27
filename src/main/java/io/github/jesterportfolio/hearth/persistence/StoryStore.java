package io.github.jesterportfolio.hearth.persistence;

import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** 검증된 서버 컨텍스트 전용. 투표 자체는 메모리, 확정 요청/결과만 영속화합니다. */
public interface StoryStore {
    record Branch(String stage,String choice,String label,UUID request){}
    record Snapshot(ExplorationStore.Story story,Set<String> completed,Map<String,Branch> branches){public Snapshot{completed=Set.copyOf(completed);branches=Map.copyOf(branches);}}
    record Request(UUID id,long revision,String from,String to,String title,boolean open,String choice,String label,Set<UUID> players,Set<String> required,List<ExplorationContent.Fact> facts){public Request{players=Set.copyOf(players);required=Set.copyOf(required);facts=List.copyOf(facts);}}
    CompletionStage<Snapshot> readStory(ServerProgressAccess.Context ctx);
    CompletionStage<Map<UUID,Boolean>> storyConditions(ServerProgressAccess.Context ctx,Set<UUID> players,Set<String> quests);
    CompletionStage<Snapshot> commitStory(ServerProgressAccess.Context ctx,Request request,BooleanSupplier fence);
    CompletionStage<Optional<String>> storyReceipt(ServerProgressAccess.Context ctx,UUID request);
    static String fingerprint(Request r){try{
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);out.writeInt(1);out.writeLong(r.revision());
        for(String value:new String[]{r.from(),r.to(),r.title(),r.choice(),r.label()})field(out,value);out.writeBoolean(r.open());
        out.writeInt(r.players().size());for(UUID id:new TreeSet<>(r.players())){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
        out.writeInt(r.required().size());for(String id:new TreeSet<>(r.required()))field(out,id);
        out.writeInt(r.facts().size());for(var f:r.facts()){for(String value:new String[]{f.id(),f.category(),f.title(),f.summary(),f.detail()})field(out,value);out.writeInt(f.version());out.writeBoolean(f.important());}
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void field(java.io.DataOutputStream out,String value)throws java.io.IOException{if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
}
