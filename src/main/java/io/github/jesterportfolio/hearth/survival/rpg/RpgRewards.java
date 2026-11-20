package io.github.jesterportfolio.hearth.survival.rpg;

import java.util.*;
import org.bukkit.*;import org.bukkit.block.Block;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.block.*;import org.bukkit.event.entity.*;import org.bukkit.event.player.*;
import dev.aurelium.auraskills.api.event.skill.XpGainEvent;import dev.aurelium.auraskills.api.event.loot.LootDropEvent;import dev.aurelium.auraskills.api.event.mana.ManaAbilityActivateEvent;

/** 기본/파생 Bukkit 이벤트는 한 번만 구독한다. Aura direct inventory loot도 같은 결정으로 차단한다. */
public final class RpgRewards implements Listener,AutoCloseable {
    public record BlockKey(UUID player,UUID world,int x,int y,int z,int tick){}
    private record Action(Object event,UUID player,UUID id){}
    private final RpgRuntime r;public final RewardCoordinator coordinator=new RewardCoordinator(System::currentTimeMillis,java.util.concurrent.ThreadLocalRandom.current());
    private final Map<UUID,Integer> deniedTree=new HashMap<>();private final Map<BlockKey,UUID> blocks=new HashMap<>();private final Map<UUID,UUID> mobs=new HashMap<>();private final Deque<Action> stack=new ArrayDeque<>();private final Set<XpGainEvent> xpEvents=Collections.newSetFromMap(new WeakHashMap<>());
    public RpgRewards(RpgRuntime r){this.r=r;Bukkit.getPluginManager().registerEvents(this,r.plugin);}
    private BlockKey key(Player p,Block b){return new BlockKey(p.getUniqueId(),b.getWorld().getUID(),b.getX(),b.getY(),b.getZ(),Bukkit.getCurrentTick());}
    public UUID begin(Player p){var id=UUID.randomUUID();try{coordinator.begin(id,r.penalized(p));}catch(Exception failure){r.hold(failure);throw failure;}return id;}
    private RewardCoordinator.Decision decision(Player p,UUID id){return coordinator.begin(id,r.penalized(p));}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void breakStart(BlockBreakEvent e){if(!r.ready())return;UUID id;try{id=begin(e.getPlayer());}catch(Exception failure){e.setCancelled(true);return;}blocks.put(key(e.getPlayer(),e.getBlock()),id);stack.push(new Action(e,e.getPlayer().getUniqueId(),id));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void blockXp(BlockBreakEvent e){var id=blocks.get(key(e.getPlayer(),e.getBlock()));if(id!=null&&decision(e.getPlayer(),id).suppressVanillaXp())e.setExpToDrop(0);}
    @EventHandler(priority=EventPriority.MONITOR) public void breakEnd(BlockBreakEvent e){stack.removeIf(a->a.event()==e);if(e.getBlock().hasMetadata("AureliumSkills-Treecapitator")){if(e.isCancelled())deniedTree.put(e.getPlayer().getUniqueId(),Bukkit.getCurrentTick());else deniedTree.remove(e.getPlayer().getUniqueId());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void blockItems(BlockDropItemEvent e){if(!r.ready())return;UUID id;try{id=blocks.computeIfAbsent(key(e.getPlayer(),e.getBlock()),k->begin(e.getPlayer()));}catch(Exception failure){e.setCancelled(true);e.getItems().forEach(org.bukkit.entity.Entity::remove);return;}if(decision(e.getPlayer(),id).suppressItems()){e.setCancelled(true);e.getItems().forEach(org.bukkit.entity.Entity::remove);}}
    @EventHandler(priority=EventPriority.LOWEST) public void mobStart(EntityDeathEvent e){if(!r.ready()||e instanceof PlayerDeathEvent||e.getEntity().getKiller()==null)return;var player=e.getEntity().getKiller();UUID id;try{id=begin(player);}catch(Exception failure){e.setCancelled(true);return;}mobs.put(e.getEntity().getUniqueId(),id);stack.push(new Action(e,player.getUniqueId(),id));}
    @EventHandler(priority=EventPriority.HIGHEST) public void mobItems(EntityDeathEvent e){if(e instanceof PlayerDeathEvent||e.getEntity().getKiller()==null)return;var id=mobs.get(e.getEntity().getUniqueId());if(id==null)return;var d=decision(e.getEntity().getKiller(),id);if(d.suppressItems())e.getDrops().clear();if(d.suppressVanillaXp())e.setDroppedExp(0);}
    @EventHandler(priority=EventPriority.MONITOR) public void mobEnd(EntityDeathEvent e){stack.removeIf(a->a.event()==e);mobs.remove(e.getEntity().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void auraLoot(LootDropEvent e){if(!r.ready())return;var p=e.getPlayer();if(r.policy.abilitiesBlocked().contains(r.role(p.getWorld()))){e.setCancelled(true);return;}var action=stack.stream().filter(a->a.player().equals(p.getUniqueId())).findFirst();if(action.isPresent()){if(decision(p,action.get().id()).suppressItems())e.setCancelled(true);return;}
        // Unattributed non-fishing loot is refused under penalty instead of re-rolling and bypassing.
        if(r.penalized(p)&&!Set.of(LootDropEvent.Cause.TREASURE_HUNTER,LootDropEvent.Cause.EPIC_CATCH,LootDropEvent.Cause.FISHING_OTHER_LOOT).contains(e.getCause()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void xp(XpGainEvent e){if(!r.ready()||!xpEvents.add(e))return;var p=e.getPlayer();if(e.getSkill().getId().toString().equals("auraskills/foraging")&&Objects.equals(deniedTree.remove(p.getUniqueId()),Bukkit.getCurrentTick())){e.setCancelled(true);return;}if(!r.aura.loaded(p.getUniqueId())||r.policy.xpBlocked().contains(r.role(p.getWorld()))){e.setCancelled(true);return;}if(r.penalized(p))e.setAmount(e.getAmount()*.5);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fishStart(PlayerFishEvent e){if(!r.ready()||e.getState()!=PlayerFishEvent.State.CAUGHT_FISH)return;UUID id;try{id=begin(e.getPlayer());}catch(Exception failure){e.setCancelled(true);return;}stack.push(new Action(e,e.getPlayer().getUniqueId(),id));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fishFinal(PlayerFishEvent e){var found=stack.stream().filter(a->a.event()==e).findFirst();if(found.isEmpty())return;var d=decision(e.getPlayer(),found.get().id());if(d.suppressItems()&&e.getCaught()!=null)e.getCaught().remove();if(d.suppressVanillaXp())e.setExpToDrop(0);}
    @EventHandler(priority=EventPriority.MONITOR) public void fishEnd(PlayerFishEvent e){stack.removeIf(a->a.event()==e);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void ability(ManaAbilityActivateEvent e){if(!r.ready()||!r.abilityAllowed(e.getPlayer()))e.setCancelled(true);}
    public UUID blockAction(Player p,Block block){return blocks.computeIfAbsent(key(p,block),unused->begin(p));}
    /** 커스텀 인챈트/자동 판매는 최종 결과를 여기로 제출한다. 같은 producer ID는 중복 지급하지 않는다. */
    public boolean deliver(UUID action,String producer,List<org.bukkit.inventory.ItemStack> finalItems,java.util.function.Consumer<List<org.bukkit.inventory.ItemStack>> giveOrSell){return coordinator.deliver(action,producer,finalItems,giveOrSell);}
    public void gainXp(Player p,String skill,double amount){if(!Double.isFinite(amount)||amount<0||!r.aura.loaded(p.getUniqueId()))throw new IllegalArgumentException("RPG_XP");var s=Objects.requireNonNull(r.aura.skill(skill));r.aura.api.getUser(p.getUniqueId()).addSkillXp(s,amount);}
    public void expire(){int tick=Bukkit.getCurrentTick();deniedTree.values().removeIf(t->t!=tick);blocks.keySet().removeIf(k->tick-k.tick()>2);coordinator.expire();}
    public void close(){HandlerList.unregisterAll(this);blocks.clear();mobs.clear();stack.clear();xpEvents.clear();}
}
