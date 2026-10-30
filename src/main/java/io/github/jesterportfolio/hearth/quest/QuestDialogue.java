package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 실제 어댑터와 제어 시험이 공유하는 현재 대화 검사. 클라이언트는 진행 소유권을 선택하지 않습니다. */
public final class QuestDialogue {
    public record Ticket(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String quest,UUID nonce,String action) {}
    public record Request(String action,String npc,String quest,UUID nonce) {
        public static Request parse(String command){
            String[] parts=command.split(" ",-1);
            if(parts.length!=5 || !parts[0].equals("/hearthquest") || !Set.of("select","accept","report","decline").contains(parts[1])
                || !parts[2].matches("[a-z][a-z0-9_]{0,63}") || !parts[3].matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid quest request");
            UUID nonce=UUID.fromString(parts[4]);if(!nonce.toString().equals(parts[4]))throw new IllegalArgumentException("Invalid conversation nonce");
            return new Request(parts[1],parts[2],parts[3],nonce);
        }
    }
    private final QuestCatalog catalog;
    private final Map<UUID,Map<String,Ticket>> current=new HashMap<>();
    public record Conversation(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,UUID nonce){}
    private final Map<UUID,Conversation> conversations=new HashMap<>();
    public Conversation conversation(UUID player){return Objects.requireNonNull(conversations.get(player),"No conversation");}
    public boolean current(Conversation c){return conversations.get(c.player())==c;}
    public String menuCommand(Conversation scope){if(!current(scope))throw new IllegalArgumentException("STALE_MENU");return "/hearthtalk menu "+scope.npc()+" "+scope.nonce();}
    public String endCommand(Conversation scope){return menuCommand(scope).replace("/hearthtalk menu ","/hearthtalk end ");}
    public Conversation authorizeMenu(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,UUID nonce){
        var scope=conversations.get(player);
        if(scope==null||scope.session()!=session||!scope.run().equals(run)||!scope.world().equals(world)||!scope.entity().equals(entity)||!scope.npc().equals(npc)||!scope.nonce().equals(nonce))throw new IllegalArgumentException("STALE_MENU");return scope;
    }
    public TopicTicket topicSelection(UUID player,Object session,String npc,String topic){
        var scope=conversation(player);var rows=topics.get(player);var ticket=rows==null?null:rows.get(topic);
        if(scope.session()!=session||!scope.npc().equals(npc)||ticket==null||consumed.contains(ticket.nonce()))throw new IllegalArgumentException("STALE_TOPIC");return ticket;
    }
    public record Page(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,int index,UUID nonce){}
    private final Map<UUID,Page> pages=new HashMap<>();
    public Optional<Page> beginPages(UUID player,Object session,UUID run,UUID world,UUID entity,String npc){
        pages.remove(player);if(catalog.pages(npc).isEmpty())return Optional.empty();var page=new Page(player,session,run,world,entity,npc,0,UUID.randomUUID());pages.put(player,page);return Optional.of(page);
    }
    public boolean current(Page page){return pages.get(page.player())==page;}
    public String pageCommand(Page page,String action){if(!current(page)||!Set.of("next","back","close").contains(action))throw new IllegalArgumentException("Stale page");return "/hearthtalk "+action+" "+page.npc()+" "+page.nonce();}
    public Optional<Page> turn(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,UUID nonce,String action){
        Page old=pages.get(player);if(old==null||old.session()!=session||!old.run().equals(run)||!old.world().equals(world)||!old.entity().equals(entity)||!old.npc().equals(npc)||!old.nonce().equals(nonce))throw new IllegalArgumentException("Stale page request");
        if(action.equals("close")){clear(player);return Optional.empty();}
        int index=old.index()+switch(action){case "next"->1;case "back"->-1;default->throw new IllegalArgumentException("Unknown page action");};
        if(index<0||index>=catalog.pages(npc).size())throw new IllegalArgumentException("Invalid page index");
        var next=new Page(player,session,run,world,entity,npc,index,UUID.randomUUID());pages.put(player,next);return Optional.of(next);
    }
    public record TopicTicket(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String topic,UUID nonce){}
    private final Map<UUID,Map<String,TopicTicket>> topics=new HashMap<>();
    public List<TopicTicket> topicOptions(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,List<QuestCatalog.Topic> available){
        clearTopics(player);var issued=new LinkedHashMap<String,TopicTicket>();
        for(var topic:available){if(catalog.topic(npc,topic.id())!=topic)throw new IllegalArgumentException("Unknown topic definition");issued.put(topic.id(),new TopicTicket(player,session,run,world,entity,npc,topic.id(),UUID.randomUUID()));}
        topics.put(player,issued);return List.copyOf(issued.values());
    }
    public boolean current(TopicTicket t){var rows=topics.get(t.player());return rows!=null&&rows.get(t.topic())==t;}
    public TopicTicket authorizeTopic(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String topic,UUID nonce){
        var rows=topics.get(player);var t=rows==null?null:rows.get(topic);
        if(t==null||t.session()!=session||!t.run().equals(run)||!t.world().equals(world)||!t.entity().equals(entity)||!t.npc().equals(npc)||!t.nonce().equals(nonce)||!consumed.add(nonce))throw new IllegalArgumentException("Stale topic request");return t;
    }
    public String topicCommand(TopicTicket t){if(!current(t))throw new IllegalArgumentException("Stale topic");return "/hearthtalk ask "+t.npc()+" "+t.topic()+" "+t.nonce();}
    private void clearTopics(UUID player){var rows=topics.remove(player);if(rows!=null)rows.values().forEach(t->consumed.remove(t.nonce()));}
    private final Set<UUID> consumed=new HashSet<>();
    public QuestDialogue(QuestCatalog catalog){this.catalog=catalog;}
    public List<Ticket> options(UUID player,Object session,UUID run,UUID world,UUID entity,String npc){
        var content=catalog.forNpc(npc);clear(player);conversations.put(player,new Conversation(player,session,run,world,entity,npc,UUID.randomUUID()));var tickets=new LinkedHashMap<String,Ticket>();
        for(var c:content)tickets.put(c.id(),new Ticket(player,session,run,world,entity,npc,c.id(),UUID.randomUUID(),"select"));
        current.put(player,tickets);return List.copyOf(tickets.values());
    }
    /** 서버가 소유한 현재 아이템창의 선택만 기존 nonce 검증 경로로 전달합니다. */
    public Ticket selection(UUID player,Object session,String npc,String quest){
        var scope=conversation(player);var rows=current.get(player);var ticket=rows==null?null:rows.get(quest);
        if(scope.session()!=session||!scope.npc().equals(npc)||ticket==null||!ticket.action().equals("select")||consumed.contains(ticket.nonce()))throw new IllegalArgumentException("Stale GUI dialogue");
        return ticket;
    }
    public boolean current(Ticket ticket){var rows=current.get(ticket.player());return rows!=null && rows.get(ticket.quest())==ticket;}
    /** 선택 응답에서만 새 수락/보고 토큰을 만듭니다. 다른 퀘스트 선택지는 그대로 유지합니다. */
    public Ticket action(Ticket selected,String action){
        if(!current(selected) || !selected.action().equals("select") || !consumed.contains(selected.nonce()) || !Set.of("accept","report").contains(action))throw new IllegalArgumentException("Invalid selected conversation");
        var next=new Ticket(selected.player(),selected.session(),selected.run(),selected.world(),selected.entity(),selected.npc(),selected.quest(),UUID.randomUUID(),action);
        consumed.remove(selected.nonce());current.get(selected.player()).put(selected.quest(),next);return next;
    }
    public Ticket authorize(UUID player,Object session,UUID run,UUID world,UUID entity,Request request){
        var rows=current.get(player);Ticket ticket=rows==null?null:rows.get(request.quest());
        if(ticket==null || ticket.session()!=session || !ticket.run().equals(run) || !ticket.world().equals(world) || !ticket.entity().equals(entity)
            || !ticket.npc().equals(request.npc()) || !ticket.quest().equals(request.quest()) || !ticket.nonce().equals(request.nonce())
            || !ticket.action().equals(request.action()) || !catalog.linked(request.npc(),request.quest()) || !consumed.add(ticket.nonce()))throw new IllegalArgumentException("Stale or invalid conversation");
        return ticket;
    }
    public String declineCommand(Ticket ticket){if(!current(ticket)||!ticket.action().equals("accept"))throw new IllegalArgumentException("DECLINE_UNAVAILABLE");return "/hearthquest decline "+ticket.npc()+" "+ticket.quest()+" "+ticket.nonce();}
    public void decline(UUID player,Object session,UUID run,UUID world,UUID entity,Request request){
        if(!request.action().equals("decline"))throw new IllegalArgumentException("DECLINE_UNAVAILABLE");
        authorize(player,session,run,world,entity,new Request("accept",request.npc(),request.quest(),request.nonce()));clear(player);
    }
    public String command(Ticket t,String action){if(!current(t) || !t.action().equals(action))throw new IllegalArgumentException("Invalid dialogue action");return "/hearthquest "+action+" "+t.npc()+" "+t.quest()+" "+t.nonce();}
    public void clear(UUID player){conversations.remove(player);clearTopics(player);pages.remove(player);var tickets=current.remove(player);if(tickets!=null)tickets.values().forEach(t->consumed.remove(t.nonce()));}
    public void clearWorld(UUID world){for(var c:List.copyOf(conversations.values()))if(c.world().equals(world))clear(c.player());for(var rows:List.copyOf(topics.values()))if(rows.values().stream().anyMatch(t->t.world().equals(world)))clear(rows.values().iterator().next().player());for(var page:List.copyOf(pages.values()))if(page.world().equals(world))clear(page.player());for(var rows:List.copyOf(current.values()))if(rows.values().stream().anyMatch(t->t.world().equals(world)))clear(rows.values().iterator().next().player());}
    public void clear(){conversations.clear();topics.clear();current.clear();pages.clear();consumed.clear();}
}
