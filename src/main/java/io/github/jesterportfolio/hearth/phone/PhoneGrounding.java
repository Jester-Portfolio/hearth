package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import java.util.*;

/** 공개 DTO의 전체 본문·내부 식별 키·버전을 묶습니다. 키와 버전은 AI 문맥에 내보내지 않습니다. */
public final class PhoneGrounding {
    private PhoneGrounding(){}
    public record Material(PhoneHelp.Selection item,long revision,int objective){}
    public record Identity(String section,String key,GameInformation content,long revision,int objective){}
    public record Scope(String mode,List<Identity> items){public Scope{items=List.copyOf(items);}}
    public record Plan(String mode,String question,boolean single,List<Material> materials,Set<String> included){
        public Plan{
            materials=List.copyOf(materials);included=Set.copyOf(included);
            if(!Set.of("quests","map","diary","story").contains(mode)||question==null||question.isBlank()||question.codePointCount(0,question.length())>256||materials.size()>4||single&&materials.size()!=1)throw new IllegalArgumentException("AI_MATERIAL_PLAN");
            var keys=new HashSet<String>();for(var m:materials)if(!m.item().section().equals(mode)||!keys.add(m.item().key()))throw new IllegalArgumentException("AI_MATERIAL_IDENTITY");
            if(!keys.containsAll(included)||single&&included.size()!=1)throw new IllegalArgumentException("AI_MATERIAL_SELECTION");
        }
        public List<Material> selected(){return materials.stream().filter(m->included.contains(m.item().key())).toList();}
        public boolean sendable(){return materials.isEmpty()||!included.isEmpty();}
        public PhoneRetrieval.Result result(){if(!sendable())throw new IllegalStateException("AI_NO_SELECTED_MATERIAL");return single?PhoneHelp.result(materials.getFirst().item()):PhoneRetrieval.present(selected().stream().map(m->m.item().source()).toList());}
        public Scope scope(){return new Scope(mode,selected().stream().map(m->new Identity(mode,m.item().key(),m.item().selected(),m.revision(),m.objective())).toList());}
        public Plan toggle(String key){if(single||materials.stream().noneMatch(m->m.item().key().equals(key)))throw new IllegalArgumentException("AI_MATERIAL_SELECTION");var next=new HashSet<>(included);if(!next.remove(key))next.add(key);return new Plan(mode,question,false,materials,next);}
        public Plan sent(){if(!sendable())throw new IllegalStateException("AI_NO_SELECTED_MATERIAL");return new Plan(mode,question,single,selected(),included);}
    }
    public static Plan search(QuestCatalog catalog,QuestJournal.View view,GameInformation data,String mode,String question){
        var result=PhoneRetrieval.find(data,mode,question);var items=result.sources().stream().map(s->material(catalog,view,data,mode,s.index())).toList();
        return new Plan(mode,question,false,items,items.stream().map(m->m.item().key()).collect(java.util.stream.Collectors.toSet()));
    }
    public static Plan single(QuestCatalog catalog,QuestJournal.View view,GameInformation data,String question,PhoneHelp.Selection expected){
        var current=PhoneHelp.refresh(catalog,view,data,expected);var item=material(catalog,view,data,current.section(),current.source().index());return new Plan(current.section(),question,true,List.of(item),Set.of(current.key()));
    }
    private static Material material(QuestCatalog catalog,QuestJournal.View view,GameInformation data,String mode,int index){
        if(mode.equals("story")){
            var selected=new GameInformation(List.of(),List.of(),List.of(),List.of(data.story().get(index)));
            var source=new PhoneRetrieval.Source(mode,index,"현재 공유 정보 "+(index+1),AssistantRequest.limit(data.story().get(index),400));
            return new Material(new PhoneHelp.Selection(mode,"story:"+index,selected,source),view.exploration().story().revision(),index);
        }
        var item=PhoneHelp.select(catalog,view,data,mode,index);long revision=-1;int objective=-1;
        if(mode.equals("quests")){var saved=view.states().get(item.key());revision=saved.revision();objective=saved.objectiveIndex();}
        else if(mode.equals("diary"))revision=view.exploration().facts().get(index).version();
        return new Material(item,revision,objective);
    }
    public static Plan refresh(QuestCatalog catalog,QuestJournal.View view,GameInformation data,Plan expected){
        if(expected.materials().isEmpty()){
            var current=search(catalog,view,data,expected.mode(),expected.question());if(!current.materials().isEmpty())throw new IllegalStateException("STALE_AI_MATERIAL");return current;
        }
        var items=new ArrayList<Material>();
        for(var old:expected.materials()){
            var item=old.item();int index;
            if(item.section().equals("story")){index=item.source().index();if(index<0||index>=data.story().size())throw new IllegalStateException("STALE_AI_MATERIAL");}
            else index=PhoneHelp.refresh(catalog,view,data,item).source().index();
            var current=material(catalog,view,data,expected.mode(),index);
            if(!current.item().key().equals(item.key())||!current.item().selected().equals(item.selected())||current.revision()!=old.revision()||current.objective()!=old.objective())throw new IllegalStateException("STALE_AI_MATERIAL");
            items.add(current);
        }
        return new Plan(expected.mode(),expected.question(),expected.single(),items,expected.included());
    }
    public static PhonePages.Detail detail(Material material){var item=material.item();return item.section().equals("story")?PhonePages.text(item.source().title(),item.selected().story()):PhonePages.detail(item.selected(),item.section(),0);}
}
