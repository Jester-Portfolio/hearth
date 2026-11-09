package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 본인의 의뢰/기록과 현재 공개 코스에 연결된 인물만 안내합니다. 원본 위치·대화 답변을 내보내지 않습니다. */
public final class NpcGuidance {
    private NpcGuidance(){}
    public static final int PAGE_SIZE=6;
    public record Contact(String npc,String name,List<String> reasons,List<String> questions,Map<String,QuestStore.Saved> quests,Map<String,Integer> facts){
        public Contact{reasons=List.copyOf(reasons);questions=List.copyOf(questions);quests=Map.copyOf(quests);facts=Map.copyOf(facts);}
        public List<String> lines(){var lines=new ArrayList<String>();lines.add(name);lines.addAll(reasons);questions.forEach(q->lines.add("현재 질문: "+q));lines.add("NPC 가까이에서 대화를 선택하세요. 지역·상호작용 권한을 다시 확인합니다.");return List.copyOf(lines);}
        public QuestBookView book(){return new QuestBookView(name,QuestBookView.paginate(lines()));}
    }
    public record Page(List<Contact> contacts,long sharedRevision,long completionEpoch){
        public Page{contacts=List.copyOf(contacts);}
        public int pages(){return Math.max(1,(contacts.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        public List<Contact> page(int number){if(number<1||number>pages())throw new IllegalArgumentException("NPC_GUIDANCE_PAGE");int start=Math.min(contacts.size(),(number-1)*PAGE_SIZE);return contacts.subList(start,Math.min(contacts.size(),start+PAGE_SIZE));}
        public QuestBookView book(int number){var lines=new ArrayList<String>();lines.add("인물 안내 · "+number+" / "+pages()+"쪽");lines.add("본인의 의뢰·개인 기록과 현재 공개 코스에 연결된 인물입니다.");if(page(number).isEmpty())lines.add("현재 안내할 수 있는 인물이 없습니다.");else page(number).forEach(c->{lines.add(c.name());lines.addAll(c.reasons());});return new QuestBookView("인물 안내",QuestBookView.paginate(lines));}
    }
    private static final class Known {
        final Set<String> reasons=new LinkedHashSet<>();final Map<String,QuestStore.Saved> quests=new HashMap<>();final Map<String,Integer> facts=new HashMap<>();
    }
    public static Page build(QuestCatalog catalog,QuestJournal.View view,QuestJourneyView.Page journey,Set<String> sharedGuides){
        var known=new LinkedHashMap<String,Known>();
        for(var row:QuestListView.entries(catalog,view.states())){
            var q=catalog.quest(row.questId());var saved=view.states().get(q.id());
            if(view.exploration().blockedQuests().contains(q.id())||saved.phase()==QuestStore.Phase.SKIPPED)continue;
            var k=known.computeIfAbsent(q.npcId(),id->new Known());k.reasons.add("내 의뢰: "+q.title()+" · "+row.status());k.quests.put(q.id(),saved);
            if(saved.phase()==QuestStore.Phase.ACTIVE){String interaction=q.goal(saved).interaction();if(interaction!=null&&interaction.startsWith("npc:")){var target=known.computeIfAbsent(interaction.substring(4),id->new Known());target.reasons.add("현재 의뢰 목표: "+q.title());target.quests.put(q.id(),saved);}}
        }
        for(var fact:view.exploration().facts())if(!fact.shared())for(var contact:NpcContacts.forFact(catalog,view,"P:"+fact.id())){var k=known.computeIfAbsent(contact.npc(),id->new Known());k.reasons.add("내 기록: "+fact.title());k.facts.put(fact.id(),fact.version());}
        if(journey!=null){
            String id=journey.guide().quest();
            if(id!=null&&!view.exploration().blockedQuests().contains(id)&&QuestListView.available(catalog.quest(id),view.states())){var q=catalog.quest(id);var k=known.computeIfAbsent(q.npcId(),npc->new Known());k.reasons.add("현재 코스: "+q.title());k.quests.put(id,view.states().get(id));}
            if(journey.links().stream().anyMatch(link->link.action()==QuestJourneyView.Action.SHARED))for(String npc:sharedGuides){catalog.npc(npc);known.computeIfAbsent(npc,key->new Known()).reasons.add("현재 공동 조사 안내");}
        }
        var contacts=new ArrayList<Contact>();
        for(var entry:known.entrySet())if(!view.blockedNpcs().contains(entry.getKey())){var npc=catalog.npc(entry.getKey());var questions=catalog.availableTopics(npc.npcId(),view.states(),id->!view.exploration().blockedQuests().contains(id)).stream().map(QuestCatalog.Topic::label).toList();var k=entry.getValue();contacts.add(new Contact(npc.npcId(),npc.name(),List.copyOf(k.reasons),questions,k.quests,k.facts));}
        contacts.sort(Comparator.comparing(Contact::name).thenComparing(Contact::npc));
        return new Page(contacts,view.exploration().story().revision(),view.completion().epoch());
    }
    public static void require(Page current,Page shown,Contact selected){if(!current.equals(shown)||selected!=null&&!shown.contacts().contains(selected))throw new IllegalStateException("STALE_NPC_GUIDANCE");}
}
