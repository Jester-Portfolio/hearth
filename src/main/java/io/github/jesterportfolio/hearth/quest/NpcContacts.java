package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 본인이 기록한 사실과 현재 허용된 질문 연결만 표시합니다. 좌표나 비공개 답변은 반환하지 않습니다. */
public final class NpcContacts {
    private NpcContacts(){}
    public record Contact(String npc,String name,String key){}
    public static List<Contact> forFact(QuestCatalog catalog,QuestJournal.View view,String factKey){
        var fact=view.exploration().facts().stream().filter(f->!f.shared()&&("P:"+f.id()).equals(factKey)).findFirst();
        if(fact.isEmpty())return List.of();String id=fact.get().id();var result=new ArrayList<Contact>();
        for(var npc:catalog.npcs())if(catalog.availableTopics(npc.npcId(),view.states(),q->!view.exploration().blockedQuests().contains(q)).stream().anyMatch(t->id.equals(t.journal())))result.add(new Contact(npc.npcId(),npc.name(),id+":"+npc.npcId()));
        return List.copyOf(result);
    }
    public static Optional<Contact> resolve(QuestCatalog catalog,QuestJournal.View view,String key){
        if(key==null)return Optional.empty();String[] parts=key.split(":",-1);
        if(parts.length!=2||!parts[0].matches("[a-z][a-z0-9_]{0,63}")||!parts[1].matches("[a-z][a-z0-9_]{0,63}"))return Optional.empty();
        return forFact(catalog,view,"P:"+parts[0]).stream().filter(c->c.key().equals(key)).findFirst();
    }
}
