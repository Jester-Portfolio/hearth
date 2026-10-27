package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 실제 어댑터와 제어 시험이 공유하는 현재 대화 검사. 클라이언트는 진행 소유권을 선택하지 않습니다. */
public final class QuestDialogue {
    public record Ticket(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String quest,UUID nonce,String action) {}
    public record Request(String action,String npc,String quest,UUID nonce) {
        public static Request parse(String command){
            String[] parts=command.split(" ",-1);
            if(parts.length!=5 || !parts[0].equals("/hearthquest") || !Set.of("select","accept","report").contains(parts[1])
                || !parts[2].matches("[a-z][a-z0-9_]{0,63}") || !parts[3].matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid quest request");
            UUID nonce=UUID.fromString(parts[4]);if(!nonce.toString().equals(parts[4]))throw new IllegalArgumentException("Invalid conversation nonce");
            return new Request(parts[1],parts[2],parts[3],nonce);
        }
    }
    private final QuestCatalog catalog;
    private final Map<UUID,Map<String,Ticket>> current=new HashMap<>();
    private final Set<UUID> consumed=new HashSet<>();
    public QuestDialogue(QuestCatalog catalog){this.catalog=catalog;}
    public List<Ticket> options(UUID player,Object session,UUID run,UUID world,UUID entity,String npc){
        var content=catalog.forNpc(npc);clear(player);var tickets=new LinkedHashMap<String,Ticket>();
        for(var c:content)tickets.put(c.id(),new Ticket(player,session,run,world,entity,npc,c.id(),UUID.randomUUID(),"select"));
        current.put(player,tickets);return List.copyOf(tickets.values());
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
    public String command(Ticket t,String action){if(!current(t) || !t.action().equals(action))throw new IllegalArgumentException("Invalid dialogue action");return "/hearthquest "+action+" "+t.npc()+" "+t.quest()+" "+t.nonce();}
    public void clear(UUID player){var tickets=current.remove(player);if(tickets!=null)tickets.values().forEach(t->consumed.remove(t.nonce()));}
    public void clearWorld(UUID world){for(var rows:List.copyOf(current.values()))if(rows.values().stream().anyMatch(t->t.world().equals(world)))clear(rows.values().iterator().next().player());}
    public void clear(){current.clear();consumed.clear();}
}
