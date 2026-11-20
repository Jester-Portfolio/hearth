package io.github.jesterportfolio.hearth.survival.economy;

import java.nio.file.*;
import java.util.*;
import io.github.jesterportfolio.hearth.administration.RuntimeLease;

/** 거래마다 작은 보류 표식 하나만 씁니다. 슬롯/아이템 원장이나 자동 재지급은 없습니다. */
public final class NativeTradeJournal {
    public record Pending(UUID request,String kind,List<UUID> players){public Pending{players=List.copyOf(players);if(request==null||!Set.of("SHOP","PEER","ENCHANT","UPGRADE").contains(kind)||players.size()!=(kind.equals("PEER")?2:1)||new HashSet<>(players).size()!=players.size())throw new IllegalArgumentException("NATIVE_TRADE_MARKER");}}
    public final class Ticket{private final Pending pending;private final RuntimeLease.Lease lease;private Ticket(Pending p,RuntimeLease.Lease l){pending=p;lease=l;}public void clean()throws Exception{lease.clean();held.remove(pending.request(),pending);}}
    private final Path directory;private final UUID group;private final Map<UUID,Pending> held=new LinkedHashMap<>();
    public NativeTradeJournal(Path data,UUID group)throws Exception{this.group=group;if(!data.equals(data.toRealPath()))throw new IllegalStateException("NATIVE_TRADE_PATH");directory=data.resolve("native-economy-pending");if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(directory);if(!directory.equals(directory.toRealPath())||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("NATIVE_TRADE_PATH");try(var paths=Files.list(directory)){for(var path:paths.toList()){if(held.size()>=128||!path.getFileName().toString().matches("[a-f0-9-]{36}\\.pending")||!path.equals(path.toRealPath())||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>1024)throw new IllegalStateException("NATIVE_TRADE_MARKER");var lines=Files.readAllLines(path);if(lines.size()!=6||!lines.get(0).equals("Hearth native trade v1")||!lines.get(1).equals(group.toString()))throw new IllegalStateException("NATIVE_TRADE_MARKER");var request=UUID.fromString(path.getFileName().toString().substring(0,36));var players=Arrays.stream(lines.get(3).split(",")).map(UUID::fromString).toList();var p=new Pending(request,lines.get(2),players);held.put(request,p);}}}
    public Ticket acquire(Pending p)throws Exception{if(held.size()>=128||held.containsKey(p.request())||p.players().stream().anyMatch(this::held))throw new IllegalStateException("NATIVE_TRADE_HELD");String ids=String.join(",",p.players().stream().map(UUID::toString).toList());var lease=RuntimeLease.acquire(directory.resolve(p.request()+".pending"),"Hearth native trade v1\n"+group+"\n"+p.kind()+"\n"+ids+"\nrequest\n").orElseThrow();held.put(p.request(),p);return new Ticket(p,lease);}
    public boolean held(UUID player){return held.values().stream().anyMatch(p->p.players().contains(player));}
    public List<Pending> pending(){return List.copyOf(held.values());}
}
