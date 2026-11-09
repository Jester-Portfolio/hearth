package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;

/** 현재 개인 목표만 현장 제작법에 연결합니다. 수락·제작·목표 저장은 실행하지 않습니다. */
public final class CraftingOrders {
    public static final int PAGE_SIZE=6;
    public record Order(String quest,String title,String npc,QuestStore.Phase phase,long revision,int objective,String recipe,String guidance) {}
    public record Page(int number,int pages,List<Order> orders) {
        public Page {orders=List.copyOf(orders);}
        /** 같은 제작 이벤트가 여러 의뢰에 반영되므로 같은 제작법은 한 번만 준비합니다. */
        public List<CraftingBatch.Selection> selections(){
            var recipes=new LinkedHashSet<String>();for(var order:orders)if(order.recipe()!=null)recipes.add(order.recipe());
            return recipes.stream().map(id->new CraftingBatch.Selection(id,1)).toList();
        }
    }
    public record Board(String facility,long sharedRevision,List<Order> orders) {
        public Board {orders=List.copyOf(orders);}
        public int pages(){return Math.max(1,(orders.size()+PAGE_SIZE-1)/PAGE_SIZE);}
        public Page page(int number){
            if(number<1||number>pages())throw new IllegalArgumentException("CRAFT_ORDERS_PAGE");
            int from=(number-1)*PAGE_SIZE;return new Page(number,pages(),orders.subList(from,Math.min(orders.size(),from+PAGE_SIZE)));
        }
    }
    private CraftingOrders(){}
    private static String recipe(CraftContent.Facility facility,String reference){
        if(reference==null)return null;
        for(String id:facility.recipes())if(reference.equals("craft:"+facility.id()+":"+id))return id;
        return null;
    }
    public static Board build(QuestCatalog catalog,CraftContent content,String facilityId,QuestJournal.View view){
        var facility=content.facilities().get(facilityId);if(facility==null)throw new IllegalArgumentException("CRAFT_ORDERS_FACILITY");
        var rows=new ArrayList<Order>();
        for(var entry:QuestListView.filtered(catalog,view.states(),view.journal().tracked(),view.exploration().blockedQuests(),QuestListView.Filter.ALL)){
            if(entry.phase()!=QuestStore.Phase.ACTIVE&&entry.phase()!=QuestStore.Phase.REPORT_READY)continue;
            var quest=catalog.quest(entry.questId());if(quest.goals().stream().noneMatch(g->recipe(facility,g.interaction())!=null))continue;
            var state=view.states().get(entry.questId());
            String selected=state.phase()==QuestStore.Phase.ACTIVE?recipe(facility,quest.goal(state).interaction()):null;
            rows.add(new Order(quest.id(),quest.title(),quest.name(),state.phase(),state.revision(),state.objectiveIndex(),selected,quest.detailGuidance(state)));
        }
        return new Board(facilityId,view.exploration().story().revision(),rows);
    }
    public static void require(Board fresh,Board shown){if(!fresh.equals(shown))throw new IllegalStateException("STALE_CRAFT_ORDERS");}
    public static void require(Board fresh,Board shown,int number,Order selected){
        require(fresh,shown);if(!shown.page(number).orders().contains(selected))throw new IllegalStateException("STALE_CRAFT_ORDER");
    }
    public static CraftingBatch.Selection step(CraftingPreparation.Step step){
        return new CraftingBatch.Selection(step.recipe(),CraftingBatch.CHOICES.contains(step.times())?step.times():1);
    }
}
