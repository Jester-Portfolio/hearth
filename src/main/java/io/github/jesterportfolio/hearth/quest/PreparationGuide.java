package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.crafting.*;
import io.github.jesterportfolio.hearth.combat.DungeonPlay;
import io.github.jesterportfolio.hearth.gathering.GatheringContent;
import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;

/** 기존 공개 DTO와 본인 재료/상태로 읽기 전용 준비 안내를 만듭니다. 입장·저장·제작을 실행하지 않습니다. */
public record PreparationGuide(List<Entry> entries,Map<String,String> materialNames){
    public enum Kind{CRAFTING,GATHERING,DUNGEON}
    public record Entry(String id,String title,Kind kind,String reference,String place,String quest,List<String> recipes,String hint){public Entry{recipes=List.copyOf(recipes);}}
    public record Facts(Map<String,List<CraftingMaterials.Need>> materials,List<String> gathering,List<String> dungeon,boolean atEntry,boolean dirty,boolean saving){public Facts{var copy=new HashMap<String,List<CraftingMaterials.Need>>();materials.forEach((k,v)->copy.put(k,List.copyOf(v)));materials=Map.copyOf(copy);gathering=List.copyOf(gathering);dungeon=List.copyOf(dungeon);}}
    public record Card(String id,String title,List<String> lines,String quest,long revision){public Card{lines=List.copyOf(lines);}}
    public record Page(List<Card> cards){public Page{cards=List.copyOf(cards);}public Card card(String id){return cards.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("PREPARATION_UNAVAILABLE"));}}
    public PreparationGuide{entries=List.copyOf(entries);materialNames=Map.copyOf(materialNames);}
    public static PreparationGuide parse(Map<?,?> root,QuestCatalog quests,CraftContent crafting,ExplorationContent exploration,GatheringContent gathering,DungeonPlay.Definition dungeon){
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1||!Boolean.TRUE.equals(root.get("public-prototype"))||!(root.get("entries") instanceof List<?> rows)||rows.isEmpty()||rows.size()>12||!(root.get("material-names") instanceof Map<?,?> labels))throw new IllegalArgumentException("PREPARATION_FORMAT");
        var names=new HashMap<String,String>();for(var label:labels.entrySet()){if(!(label.getKey() instanceof String key)||!key.matches("[A-Z_]+")||!(label.getValue() instanceof String value)||value.isBlank()||value.length()>60)throw new IllegalArgumentException("PREPARATION_LABEL");names.put(key,value);}
        var ids=new HashSet<String>();var entries=new ArrayList<Entry>();
        for(var value:rows){if(!(value instanceof Map<?,?> r))throw new IllegalArgumentException("PREPARATION_ENTRY");String id=key(r,"id"),ref=key(r,"reference");if(!ids.add(id))throw new IllegalArgumentException("PREPARATION_DUPLICATE");var kind=Kind.valueOf(text(r,"kind",20));String place=kind==Kind.DUNGEON?null:key(r,"place");String quest=kind==Kind.DUNGEON?key(r,"quest"):null;var recipes=new ArrayList<String>();
            if(kind!=Kind.DUNGEON&&!exploration.places().containsKey(place))throw new IllegalArgumentException("PREPARATION_PLACE");
            if(kind==Kind.CRAFTING){var facility=crafting.facilities().get(ref);if(facility==null||!(r.get("recipes") instanceof List<?> list)||list.isEmpty()||list.size()>8)throw new IllegalArgumentException("PREPARATION_CRAFTING");for(var recipe:list){if(!(recipe instanceof String s)||!facility.recipes().contains(s)||recipes.contains(s))throw new IllegalArgumentException("PREPARATION_RECIPE");recipes.add(s);for(String material:crafting.recipes().get(s).matrix())if(!material.equals("AIR")&&!names.containsKey(material))throw new IllegalArgumentException("PREPARATION_LABEL");}if(!exploration.places().get(place).bounds().contains(facility.x(),facility.y(),facility.z()))throw new IllegalArgumentException("PREPARATION_PLACE");}
            if(kind==Kind.GATHERING&&(!gathering.id().equals(ref)||!exploration.places().get(place).bounds().contains(gathering.spawn().x(),gathering.spawn().y(),gathering.spawn().z())))throw new IllegalArgumentException("PREPARATION_GATHERING");
            if(kind==Kind.DUNGEON&&(!dungeon.id().equals(ref)||!dungeon.quest().equals(quest)||!quests.questIds().contains(quest)||!quests.quest(quest).npcId().equals(dungeon.npc())||quests.quest(quest).goals().stream().noneMatch(g->dungeon.reference().equals(g.interaction()))))throw new IllegalArgumentException("PREPARATION_DUNGEON");
            entries.add(new Entry(id,text(r,"title",80),kind,ref,place,quest,recipes,text(r,"hint",400)));
        }return new PreparationGuide(entries,names);
    }
    public Page build(QuestCatalog catalog,CraftContent crafting,QuestJournal.View view,Facts facts){
        QuestListView.entries(catalog,view.states());var cards=new ArrayList<Card>();
        for(var e:entries){var lines=new ArrayList<String>();String quest=null;long revision=-1;
            if(e.kind()!=Kind.DUNGEON){var place=view.exploration().place(e.place());if(place.isEmpty())continue;lines.add("장소: "+place.get().name());lines.add(place.get().location());lines.add(e.hint());
                if(e.kind()==Kind.GATHERING)lines.addAll(facts.gathering());else for(String id:e.recipes()){var recipe=crafting.recipes().get(id);var needs=facts.materials().get(id);if(recipe==null||needs==null||!needs.stream().map(n->n.material()+":"+n.required()).toList().equals(CraftingMaterials.view(recipe,Map.of()).stream().map(n->n.material()+":"+n.required()).toList()))throw new IllegalStateException("PREPARATION_MATERIALS_UNAVAILABLE");lines.add("제작: "+recipe.title());for(var need:needs)lines.add(materialNames.get(need.material())+" · 필요 "+need.required()+" / 보유 "+need.available()+" / 부족 "+need.missing());} }
            else{var c=catalog.quest(e.quest());if(view.exploration().blockedQuests().contains(c.id())||!QuestListView.available(c,view.states()))continue;var row=view.states().get(c.id());lines.add(e.hint());
                if(row.phase()==QuestStore.Phase.NOT_ACCEPTED)lines.add("아직 개인 던전 의뢰를 수락하지 않았습니다. 현장 안내원과 대화하세요.");
                else{quest=c.id();revision=row.revision();lines.add("본인 의뢰: "+c.title()+" · "+QuestListView.status(row.phase()));lines.add("담당 NPC: "+c.name());
                    if(row.phase()==QuestStore.Phase.ACTIVE){boolean goal=c.goal(row).interaction()!=null&&c.goal(row).interaction().equals("dungeon:"+e.reference());lines.add(goal?"현재 개인 목표는 던전 도전입니다.":"현재 개인 목표를 먼저 확인하세요.");lines.add(facts.atEntry()?"본인은 입구 범위 안에 있습니다.":"본인은 입구 범위 밖에 있습니다.");lines.addAll(facts.dungeon());}
                    else if(row.phase()==QuestStore.Phase.REPORT_READY)lines.add("담당 NPC에게 개인 보고를 마치세요.");else if(row.phase()==QuestStore.Phase.COMPLETED)lines.add("개인 보고 완료 기록입니다. 새 도전을 자동 생성하지 않습니다.");
                }
                lines.add("제작 실습은 던전의 새 필수 입장 조건이 아닙니다.");lines.add("최종 명단·집결·권한·저장은 기존 NPC 입장 요청 시 다시 검사합니다.");
            }
            lines.add(facts.saving()?"본인의 인벤토리 저장 처리 중입니다.":facts.dirty()?"본인의 최근 변경은 저장 대기 중입니다.":"현재 처리 중인 저장은 없습니다. 입장 전에는 다시 확인합니다.");
            lines.add("읽기 전용 안내 · 이 화면에서 이동/제작/던전 입장을 실행하지 않습니다.");cards.add(new Card(e.id(),e.title(),lines,quest,revision));
        }return new Page(cards);
    }
    public static void require(Page fresh,Page shown,String id){if(!fresh.equals(shown)||!shown.cards().contains(fresh.card(id)))throw new IllegalStateException("STALE_PREPARATION");}
    private static String text(Map<?,?> r,String k,int max){if(!(r.get(k) instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException("PREPARATION_TEXT");return s;}
    private static String key(Map<?,?> r,String k){String s=text(r,k,64);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("PREPARATION_KEY");return s;}
}
