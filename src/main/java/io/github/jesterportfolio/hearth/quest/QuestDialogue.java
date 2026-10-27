package io.github.jesterportfolio.hearth.quest;

import java.util.*;

/** 실제 어댑터와 제어 시험이 공유하는 현재 대화 검사. 클라이언트는 진행 소유권을 선택하지 않습니다. */
public final class QuestDialogue {
    public record Ticket(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String quest,UUID nonce) {}
    public record Request(String action,String npc,String quest,UUID nonce) {
        public static Request parse(String command){
            String[] parts=command.split(" ",-1);
            if(parts.length!=5 || !parts[0].equals("/hearthquest") || !(parts[1].equals("accept") || parts[1].equals("report"))
                || !parts[2].matches("[a-z][a-z0-9_]{0,63}") || !parts[3].matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid quest request");
            UUID nonce=UUID.fromString(parts[4]);if(!nonce.toString().equals(parts[4]))throw new IllegalArgumentException("Invalid conversation nonce");
            return new Request(parts[1],parts[2],parts[3],nonce);
        }
    }
    private final QuestCatalog catalog;
    private final Map<UUID,Ticket> current=new HashMap<>();
    private final Set<UUID> consumed=new HashSet<>();
    public QuestDialogue(QuestCatalog catalog){this.catalog=catalog;}
    public Ticket begin(UUID player,Object session,UUID run,UUID world,UUID entity,String npc,String quest){
        if(!catalog.linked(npc,quest))throw new IllegalArgumentException("Invalid NPC/quest link");
        clear(player);var ticket=new Ticket(player,session,run,world,entity,npc,quest,UUID.randomUUID());current.put(player,ticket);return ticket;
    }
    public boolean current(Ticket ticket){return current.get(ticket.player())==ticket;}
    public Ticket authorize(UUID player,Object session,UUID run,UUID world,UUID entity,Request request){
        Ticket ticket=current.get(player);
        if(ticket==null || ticket.session()!=session || !ticket.run().equals(run) || !ticket.world().equals(world) || !ticket.entity().equals(entity)
            || !ticket.npc().equals(request.npc()) || !ticket.quest().equals(request.quest()) || !ticket.nonce().equals(request.nonce())
            || !catalog.linked(request.npc(),request.quest()) || !consumed.add(ticket.nonce()))throw new IllegalArgumentException("Stale or invalid conversation");
        return ticket;
    }
    public String command(Ticket t,String action){if(!current(t) || !(action.equals("accept") || action.equals("report")))throw new IllegalArgumentException("Invalid dialogue action");return "/hearthquest "+action+" "+t.npc()+" "+t.quest()+" "+t.nonce();}
    public void clear(UUID player){var ticket=current.remove(player);if(ticket!=null)consumed.remove(ticket.nonce());}
    public void clearWorld(UUID world){for(Ticket t:List.copyOf(current.values()))if(t.world().equals(world))clear(t.player());}
    public void clear(){current.clear();consumed.clear();}
}
