package io.github.jesterportfolio.hearth.features;

import io.github.jesterportfolio.hearth.combat.*;
import io.github.jesterportfolio.hearth.session.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

/** 새 JVM의 확정 종료 대기만 저장→claim→안전 이동→DONE으로 처리합니다. */
public final class PaperRecovery {
    private final RecoveryLedger ledger;private final PaperSessionAdapter players;private final PlayerSessions sessions;private final Executor owner;private final BiPredicate<io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context,UUID> live;
    public PaperRecovery(RecoveryLedger ledger,PaperSessionAdapter players,PlayerSessions sessions,Executor owner,BiPredicate<io.github.jesterportfolio.hearth.persistence.ServerProgressAccess.Context,UUID> live){this.ledger=ledger;this.players=players;this.sessions=sessions;this.owner=owner;this.live=live;}
    public CompletionStage<Void> ready(Player p,PlayerSessions.Session s){return ledger.read(s.context()).thenComposeAsync(row->{if(players.prepared(p)!=s)throw new IllegalStateException("RECOVERY_STALE");if(row.isEmpty()||live.test(s.context(),row.get().attempt())||RecoveryLedger.restart(row.get())==RecoveryLedger.Action.NONE)return CompletableFuture.completedFuture(null);var state=row.get();if(RecoveryLedger.restart(state)==RecoveryLedger.Action.HOLD){players.craftingHold(s);p.sendMessage(Component.text("재시작 전 전투/이동의 결과가 불명입니다. HOLD와 원본을 보존하며 관리자 확인이 필요합니다."));return CompletableFuture.failedFuture(new IllegalStateException("RECOVERY_HOLD"));}
        if(!p.getWorld().getUID().equals(state.world()))throw new IllegalStateException("RECOVERY_WORLD_CHANGED");players.freezeTravel(p,s,true);UUID claim=UUID.randomUUID();var target=state.exit().pose(state.world());var current=(BooleanSupplier)()->players.prepared(p)==s&&p.getWorld().getUID().equals(state.world());
        return sessions.saveBoundary(s).thenComposeAsync(v->ledger.claim(s.context(),state.attempt(),claim),owner).thenComposeAsync(v->p.getWorld().getChunkAtAsync(((int)Math.floor(target.x()))>>4,((int)Math.floor(target.z()))>>4),owner).thenComposeAsync(chunk->{if(!current.getAsBoolean()||!safe(p.getWorld(),target))throw new IllegalStateException("RECOVERY_UNSAFE");return players.combatMove(p,s,new Location(p.getWorld(),target.x(),target.y(),target.z(),target.yaw(),target.pitch()),()->current.getAsBoolean()&&safe(p.getWorld(),target));},owner).thenComposeAsync(ok->{if(!Boolean.TRUE.equals(ok)||!current.getAsBoolean())throw new IllegalStateException("RECOVERY_MOVE_UNKNOWN");p.setGameMode(GameMode.valueOf(state.mode()));p.setHealth(CombatPlayers.restoredHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue(),state.exit().healthFraction()));for(var e:state.exit().effects()){var type=Registry.MOB_EFFECT.get(NamespacedKey.minecraft(e.type().toLowerCase(Locale.ROOT)));if(type==null)throw new IllegalStateException("RECOVERY_EFFECT");p.addPotionEffect(new PotionEffect(type,e.ticks(),e.amplifier()));}return ledger.done(s.context(),state.attempt(),claim);},owner).thenAcceptAsync(done->{players.freezeTravel(p,s,false);p.sendMessage(Component.text("확정된 전투 종료 복귀를 마쳤습니다. 보상이나 전투를 다시 실행하지 않았습니다."));},owner).whenCompleteAsync((v,error)->{if(error!=null){players.craftingHold(s);p.sendMessage(Component.text("전투 복귀 저장/이동 확인 보류 · 원본 기록을 보존합니다."));}},owner);
    },owner).whenCompleteAsync((v,error)->{if(error!=null&&players.prepared(p)==s)players.craftingHold(s);},owner);}
    private boolean safe(World world,CombatContent.Pose target){if(!world.getWorldBorder().isInside(new Location(world,target.x(),target.y(),target.z()))||target.y()<=world.getMinHeight()||target.y()+2>=world.getMaxHeight())return false;for(double dx:new double[]{-.3,.3})for(double dz:new double[]{-.3,.3}){int x=(int)Math.floor(target.x()+dx),z=(int)Math.floor(target.z()+dz),y=(int)Math.floor(target.y());if(!world.isChunkLoaded(x>>4,z>>4))return false;var floor=world.getBlockAt(x,y-1,z);var feet=world.getBlockAt(x,y,z);var head=world.getBlockAt(x,y+1,z);if(!CombatPlayers.safeCells(floor.getType().name(),feet.getType().name(),head.getType().name(),floor.getType().isSolid(),feet.isPassable(),head.isPassable()))return false;}return true;}
}
