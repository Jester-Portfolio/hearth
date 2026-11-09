package io.github.jesterportfolio.hearth.crafting;

import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.WorldLifecycle;
import io.github.jesterportfolio.hearth.persistence.ServerProgressAccess;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 등록된 개발 시설의 메뉴만 허용합니다. 순정 임시 제작칸/보관함을 열지 않습니다. */
public final class PaperCrafting implements Listener,AutoCloseable {
    private final JavaPlugin plugin;private final PaperSessionAdapter sessions;private final Map<UUID,WorldLifecycle> worlds;
    private final PaperInventoryCodec codec;private final Executor owner;private CraftingQuest questBinding;private BiPredicate<ServerProgressAccess.Context,String> questAllowed=(ctx,id)->false;
    public void quests(CraftingQuest binding,BiPredicate<ServerProgressAccess.Context,String> allowed){main();if(questBinding!=null)throw new IllegalStateException("CRAFT_BINDING_DUPLICATE");questBinding=binding;questAllowed=allowed;}
    private final BiPredicate<ServerProgressAccess.Context,CraftContent.Facility> access;
    public final CraftContent content;public final PaperCraftingPlan planner;public final CraftingAction actions;
    private Consumer<Player> journey;
    public void journey(Consumer<Player> reader){main();journey=Objects.requireNonNull(reader);}
    private Consumer<Player> preparationGuide;
    public void preparationGuide(Consumer<Player> reader){main();preparationGuide=Objects.requireNonNull(reader);}
    private final Map<UUID,Screen> screens=new HashMap<>();private final Set<UUID> opening=new HashSet<>();private boolean closed;
    private final class Screen implements InventoryHolder {
        final Player player;final PlayerSessions.Session session;final CraftContent.Facility facility;final long generation;
        final Inventory inventory;final Map<Integer,String> choices=new HashMap<>();final String recipe;final CraftingBatch.Selection goal;final int times;final boolean preparing;CraftingAction.Ticket ticket;CraftingQuest.Prepared prepared;boolean used,frozen;
        Screen(Player p,PlayerSessions.Session s,CraftContent.Facility f,String recipe,int times,CraftingBatch.Selection goal,boolean preparing){player=p;this.preparing=preparing;this.times=CraftingBatch.require(times);this.goal=goal;session=s;facility=f;generation=worlds.get(s.context().storyRunId()).generation();this.recipe=recipe;inventory=Bukkit.createInventory(this,54,Component.text("작업대 · 격리 제작"));
            if(recipe==null){inventory.setContents(previewRecipes(content,f,planner,p.getWorld()).getContents());int slot=0;for(String id:f.recipes())choices.put(slot++,id);}
            else if(preparing){
                var plan=planner.preparation(p.getWorld(),content,f,List.of(new CraftingBatch.Selection(recipe,times)),codec.capture(p.getInventory()));
                inventory.setContents(previewPreparation(plan).getContents());int slot=0;for(var step:plan.steps())choices.put(slot++,step.recipe());
            }
            else{
                var r=content.recipes().get(recipe);var needs=planner.materials(r,codec.capture(p.getInventory()),times);
                inventory.setContents(previewDetail(planner,p.getWorld(),r,needs,times).getContents());
                var links=planner.materialRecipes(p.getWorld(),content,f,recipe,needs);int slot=9;
                for(var need:needs){String target=links.get(need.material());if(target!=null){choices.put(slot,target);var icon=inventory.getItem(slot);var lore=new ArrayList<>(need.lines());lore.add("재료 제작법: "+content.recipes().get(target).title());lore.add("왼쪽 클릭으로 제작법 확인");icon.editMeta(m->m.lore(lore.stream().map(Component::text).toList()));}slot++;}
                inventory.setItem(48,item(new ItemStack(Material.PAPER),"전체 준비 순서",List.of("보유 재료와 중간 제작법을 반영합니다","추가 원재료와 제작 후 남는 재료를 확인합니다")));
                if(goal!=null)inventory.setItem(46,item(new ItemStack(Material.COMPASS),"목표 제작으로 돌아가기",List.of(content.recipes().get(goal.recipe()).title())));
            }
            if(journey!=null)inventory.setItem(50,item(new ItemStack(Material.WRITABLE_BOOK),"다음 의뢰와 보고 안내",List.of("현재 개인 목표와 담당 NPC를 확인합니다")));
            if(preparationGuide!=null)inventory.setItem(51,item(new ItemStack(Material.MAP),"조달 장소·전체 준비 안내",List.of("현재 공개된 시설과 개인 준비 상태를 확인합니다")));
        }
        public Inventory getInventory(){return inventory;}
    }
    public PaperCrafting(JavaPlugin plugin,PaperSessionAdapter sessions,Map<UUID,WorldLifecycle> worlds,CraftContent content,PaperInventoryCodec codec,BiPredicate<ServerProgressAccess.Context,CraftContent.Facility> access,Executor owner){
        if(!plugin.getConfig().getBoolean("crafting-verification-enabled")||!Boolean.getBoolean("hearth.verification.crafting"))throw new IllegalStateException("CRAFT_ISOLATED_GATE");
        this.plugin=plugin;this.sessions=sessions;this.worlds=worlds;this.content=content;this.codec=codec;this.access=access;this.owner=owner;planner=new PaperCraftingPlan(codec);
        for(var recipe:content.recipes().values())planner.result(Bukkit.getWorlds().getFirst(),recipe);
        actions=new CraftingAction(r->Bukkit.getScheduler().runTaskLater(plugin,r,1),PaperCrafting::main,codec::equivalent);
        sessions.crafting((p,i)->i.getHolder() instanceof Screen s&&valid(s)&&s.player==p,this::registered);
        for(UUID run:worlds.keySet())if(Bukkit.getWorld(worlds.get(run).mapping().physicalId())!=null)ensureRegions(run);
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("CRAFT_MAIN_THREAD");}
    public static Inventory previewRecipes(CraftContent content,CraftContent.Facility facility,PaperCraftingPlan planner,World world){
        var inventory=Bukkit.createInventory(null,54,Component.text("작업대 · 격리 제작"));int slot=0;for(String id:facility.recipes()){var recipe=content.recipes().get(id);inventory.setItem(slot++,item(planner.result(world,recipe).getResult(),recipe.title(),PaperCraftingPlan.ingredients(recipe)));}return inventory;
    }
    public static Inventory previewDetail(PaperCraftingPlan planner,World world,CraftContent.Recipe recipe,List<CraftingMaterials.Need> needs,int times){
        CraftingBatch.require(times);var inventory=previewMaterials(needs);var output=planner.result(world,recipe).getResult();
        var summary=new ArrayList<String>();summary.add("제작 횟수: "+times+"회");summary.add("총 결과 수량: "+output.getAmount()*times);summary.add("순정 잔여물도 보관 슬롯에 반환합니다");
        inventory.setItem(22,item(output,recipe.title(),summary));
        int slot=27;for(int count:CraftingBatch.CHOICES)inventory.setItem(slot++,item(new ItemStack(count==times?Material.LIME_DYE:Material.GRAY_DYE),count+"회 제작 선택",List.of("총 재료와 결과를 다시 확인합니다",count==times?"현재 선택":"클릭하여 선택")));
        inventory.setItem(45,item(new ItemStack(Material.ARROW),"제작 목록으로",List.of()));
        inventory.setItem(49,item(new ItemStack(Material.CLOCK),"재료 다시 확인",List.of("현재 보관 슬롯의 수량을 다시 확인합니다")));
        return inventory;
    }
    public static Inventory previewPreparation(CraftingPreparation.Plan plan){
        var inventory=Bukkit.createInventory(null,54,Component.text("재료와 제작 준비 순서"));int slot=0;
        for(var step:plan.steps())inventory.setItem(slot++,item(new ItemStack(Objects.requireNonNull(Material.matchMaterial(step.output()))),step.title()+" · "+step.times()+"회",List.of("결과 수량: "+step.produced(),"왼쪽 클릭으로 제작법 확인")));
        inventory.setItem(40,preparationMaterials(Material.CHEST,"사용할 보유 재료",plan.ownedUsed()));
        inventory.setItem(41,preparationMaterials(Material.HOPPER,"추가 원재료",plan.additional()));
        inventory.setItem(42,preparationMaterials(Material.BUNDLE,"제작 후 남는 재료",plan.surplus()));
        String status=switch(plan.status()){case READY->"보유 재료로 준비할 수 있습니다";case NEEDS_MATERIALS->"추가 원재료를 준비해 주세요";case UNAVAILABLE->"준비 순서를 확인하지 못했습니다";};
        inventory.setItem(44,item(new ItemStack(plan.status()==CraftingPreparation.Status.UNAVAILABLE?Material.BARRIER:Material.BOOK),status,List.of("등록 순서에 따른 준비 예시입니다","보관 공간은 실제 제작 시 다시 검사합니다","횟수가 선택 버튼에 없으면 1회씩 나누어 제작하세요")));
        inventory.setItem(45,item(new ItemStack(Material.ARROW),"목표 제작으로",List.of()));inventory.setItem(49,item(new ItemStack(Material.CLOCK),"준비 순서 새로고침",List.of("현재 보유 재료로 다시 계산합니다")));return inventory;
    }
    private static ItemStack preparationMaterials(Material icon,String title,Map<String,Integer> counts){
        var result=item(new ItemStack(icon),title,List.of());var lore=new ArrayList<Component>();
        if(counts.isEmpty())lore.add(Component.text("없음"));else counts.forEach((material,count)->lore.add(Component.translatable(Objects.requireNonNull(Material.matchMaterial(material)).translationKey()).append(Component.text(" × "+count))));
        result.editMeta(m->m.lore(lore));return result;
    }
    public static Inventory previewMaterials(List<CraftingMaterials.Need> needs){var inventory=Bukkit.createInventory(null,54,Component.text("제작 재료 확인"));int slot=9;for(var need:needs){var icon=new ItemStack(Objects.requireNonNull(Material.matchMaterial(need.material())));icon.editMeta(m->m.lore(need.lines().stream().map(Component::text).toList()));inventory.setItem(slot++,icon);}return inventory;}
    private static ItemStack item(ItemStack source,String title,List<String> lore){var i=source.clone();i.editMeta(m->{m.displayName(Component.text(title));m.lore(lore.stream().map(Component::text).toList());});return i;}
    private boolean permitted(Player p,CraftContent.Facility f){var platform=WorldGuard.getInstance().getPlatform();var local=WorldGuardPlugin.inst().wrapPlayer(p);return platform.getSessionManager().hasBypass(local,BukkitAdapter.adapt(p.getWorld()))||platform.getRegionContainer().createQuery().testState(BukkitAdapter.adapt(new Location(p.getWorld(),f.x(),f.y(),f.z())),local,Flags.INTERACT);}
    private boolean eligible(Player p,PlayerSessions.Session s,CraftContent.Facility f,long gen){return eligible(p,s,f,gen,false);}
    private boolean eligible(Player p,PlayerSessions.Session s,CraftContent.Facility f,long gen,boolean frozen){
        if(closed||(frozen?sessions.prepared(p):sessions.current(p))!=s||!sessions.travelEligible(p,s)||p.getGameMode()!=GameMode.SURVIVAL||p.isDead()||p.isInsideVehicle()||!p.getItemOnCursor().isEmpty())return false;
        var w=worlds.get(s.context().storyRunId());if(w==null||w.generation()!=gen||!p.getWorld().getUID().equals(w.mapping().physicalId())||!access.test(s.context(),f))return false;
        return p.getLocation().distanceSquared(new Location(p.getWorld(),f.x()+0.5,f.y()+0.5,f.z()+0.5))<=16&&p.getWorld().isChunkLoaded(f.x()>>4,f.z()>>4)&&p.getWorld().getBlockAt(f.x(),f.y(),f.z()).getType()==Material.CRAFTING_TABLE&&permitted(p,f);
    }
    private boolean valid(Screen s){return screens.get(s.player.getUniqueId())==s&&eligible(s.player,s.session,s.facility,s.generation,s.frozen);}
    private CraftContent.Facility find(Block block){if(block.getType()!=Material.CRAFTING_TABLE)return null;return content.facilities().values().stream().filter(f->f.x()==block.getX()&&f.y()==block.getY()&&f.z()==block.getZ()).findFirst().orElse(null);}
    private boolean registered(Player p,Block block){var s=sessions.current(p);var f=find(block);return s!=null&&f!=null&&eligible(p,s,f,worlds.get(s.context().storyRunId()).generation());}
    /** 시설 전용 구역만 생성합니다. 블록 배치는 별도 신규 시험 fixture가 담당합니다. */
    public void ensureRegions(UUID run){main();var w=Objects.requireNonNull(Bukkit.getWorld(worlds.get(run).mapping().physicalId()));var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));for(var f:content.facilities().values()){
        var point=BlockVector3.at(f.x(),f.y(),f.z());String id="hearth_crafting_"+f.id();var old=manager.getRegion(id);
        if(old==null){var r=new ProtectedCuboidRegion(id,point,point);r.setPriority(30);r.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);r.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);manager.addRegion(r);}
        else if(!old.getMinimumPoint().equals(point)||!old.getMaximumPoint().equals(point)||old.getPriority()!=30||old.getFlag(Flags.INTERACT)!=StateFlag.State.ALLOW||old.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.ALLOW)throw new IllegalStateException("CRAFT_REGION_CONFLICT");
    }}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null||!registered(e.getPlayer(),e.getClickedBlock()))return;e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND)return;var p=e.getPlayer();if(!opening.add(p.getUniqueId()))return;var session=sessions.current(p);var f=find(e.getClickedBlock());long gen=worlds.get(session.context().storyRunId()).generation();Bukkit.getScheduler().runTaskLater(plugin,()->{opening.remove(p.getUniqueId());if(!eligible(p,session,f,gen))return;show(p,session,f,null);},1);}
    private void show(Player p,PlayerSessions.Session s,CraftContent.Facility f,String recipe){show(p,s,f,recipe,1,null);}
    private void show(Player p,PlayerSessions.Session s,CraftContent.Facility f,String recipe,int times,CraftingBatch.Selection goal){show(p,s,f,recipe,times,goal,false);}
    private void show(Player p,PlayerSessions.Session s,CraftContent.Facility f,String recipe,int times,CraftingBatch.Selection goal,boolean preparing){
        try{var screen=new Screen(p,s,f,recipe,times,goal,preparing);screens.put(p.getUniqueId(),screen);
            p.openInventory(screen.inventory);if(p.getOpenInventory().getTopInventory()!=screen.inventory){screens.remove(p.getUniqueId(),screen);return;}
            if(recipe!=null&&!preparing){try{screen.ticket=actions.preview(port(screen));screen.inventory.setItem(31,item(new ItemStack(Material.LIME_DYE),times+"회 제작 확정",List.of("총 재료와 공간을 다시 검사한 뒤 한 번 적용합니다","모두 제작할 수 없으면 원본을 보존합니다")));}catch(RuntimeException error){screen.inventory.setItem(31,item(new ItemStack(Material.BARRIER),"CURSOR_STORAGE_FULL".equals(error.getMessage())?"보관 공간이 부족합니다":"재료 또는 제작 상태를 확인해 주세요",List.of("위 재료 아이템에서 필요·보유·부족 수량을 확인하세요")));}}
        }catch(RuntimeException error){p.sendMessage(Component.text("제작 정보를 확인하지 못했습니다. 원본 인벤토리를 보존했습니다."));}
    }
    private CraftingAction.Port port(Screen s){return new CraftingAction.Port(){
        public UUID run(){return s.session.context().storyRunId();}public UUID player(){return s.player.getUniqueId();}public String source(){return "craft:"+s.facility.id()+":"+s.recipe;}public boolean valid(){return PaperCrafting.this.valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory&&(s.prepared==null||questBinding.valid(s.prepared));}
        public InventoryPayload capture(){return codec.capture(s.player.getInventory());}public InventoryPayload plan(InventoryPayload before){if(!s.facility.recipes().contains(s.recipe))throw new IllegalStateException("CRAFT_REFERENCE");return planner.plan(s.player.getWorld(),content.recipes().get(s.recipe),before,s.times);}
        public void apply(InventoryPayload after){codec.apply(s.player.getInventory(),after);}public void changed(){sessions.crafted(s.player,s.session);}public void hold(){sessions.craftingHold(s.session);}
    };}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Screen s))return;e.setCancelled(true);if(e.getWhoClicked()!=s.player||!valid(s)||s.used||e.getClickedInventory()!=s.inventory||e.getClick()!=ClickType.LEFT)return;
        if(s.recipe!=null&&e.getSlot()==31&&s.ticket!=null){s.used=true;Bukkit.getScheduler().runTaskLater(plugin,()->confirm(s),1);return;}
        if(e.getSlot()==50&&journey!=null){s.used=true;Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory)journey.accept(s.player);},1);return;}
        if(e.getSlot()==51&&preparationGuide!=null){s.used=true;Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory)preparationGuide.accept(s.player);},1);return;}
        if(s.recipe!=null&&(e.getSlot()==48&&!s.preparing||s.preparing&&(e.getSlot()==45||e.getSlot()==49))){
            boolean preparing=e.getSlot()!=45;s.used=true;Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory)show(s.player,s.session,s.facility,s.recipe,s.times,s.goal,preparing);},1);return;
        }
        String choice=s.choices.get(e.getSlot());int times=s.times;var goal=s.goal;
        if(choice!=null){times=choice.equals(s.recipe)?s.times:1;goal=s.goal==null&&s.recipe!=null&&!choice.equals(s.recipe)?new CraftingBatch.Selection(s.recipe,s.times):s.goal;}
        else if(s.recipe!=null&&!s.preparing&&e.getSlot()>=27&&e.getSlot()<27+CraftingBatch.CHOICES.size()){choice=s.recipe;times=CraftingBatch.CHOICES.get(e.getSlot()-27);}
        else if(s.recipe!=null&&e.getSlot()==45){times=1;goal=null;}
        else if(s.recipe!=null&&!s.preparing&&e.getSlot()==46&&s.goal!=null){choice=s.goal.recipe();times=s.goal.times();goal=null;}
        else if(s.recipe!=null&&e.getSlot()==49)choice=s.recipe;
        else return;
        String selected=choice;var target=goal;int count=times;s.used=true;Bukkit.getScheduler().runTaskLater(plugin,()->{if(valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory)show(s.player,s.session,s.facility,selected,count,target);},1);
    }
    private boolean connected(Screen s){var w=worlds.get(s.session.context().storyRunId());return !closed&&sessions.prepared(s.player)==s.session&&w.generation()==s.generation&&s.player.getWorld().getUID().equals(w.mapping().physicalId());}
    private void confirm(Screen s){
        if(!valid(s)||s.player.getOpenInventory().getTopInventory()!=s.inventory)return;
        long generation=s.generation;
        CompletionStage<CraftingQuest.Prepared> preparation=questBinding==null?CompletableFuture.completedFuture(null):questBinding.prepare(s.session.context(),s.facility.id(),s.recipe,s.ticket,()->!closed&&sessions.prepared(s.player)==s.session&&worlds.get(s.session.context().storyRunId()).generation()==generation,()->s.ticket.result!=null&&s.ticket.result.isDone()&&!s.ticket.result.isCompletedExceptionally()?connected(s)&&access.test(s.session.context(),s.facility)&&permitted(s.player,s.facility):valid(s),id->questAllowed.test(s.session.context(),id));
        preparation.thenComposeAsync(prepared->{if(!valid(s))throw new IllegalStateException("CRAFT_QUEST_STALE");s.prepared=prepared;return actions.confirm(s.ticket);},owner)
            .thenComposeAsync(v->{if(s.prepared==null)return CompletableFuture.completedFuture(List.<String>of());if(s.prepared.active()){sessions.freezeTravel(s.player,s.session,true);s.frozen=true;}return questBinding.complete(s.prepared,()->sessions.saveBoundary(s.player,s.session));},owner)
            .whenCompleteAsync((messages,error)->{
                if(s.frozen){sessions.freezeTravel(s.player,s.session,false);s.frozen=false;}
                if(error!=null&&questBinding!=null&&questBinding.held(s.session.context())){sessions.craftingHold(s.session);plugin.getLogger().warning("HEARTH_CRAFT_QUEST_HOLD");if(s.player.isOnline()&&sessions.linked(s.player)==s.session)s.player.sendMessage(Component.text("제작 이후 저장 정리가 보류됐습니다. 기존 데이터와 현재 인벤토리를 보존했으며 관리자 확인이 필요합니다."));}
                if(!connected(s))return;
                s.player.sendMessage(Component.text(error==null?s.times+"회 제작 완료":"제작 또는 퀘스트 반영을 마치지 못했습니다. 상태를 확인해 주세요."));
                if(error==null)for(String message:messages)s.player.sendMessage(Component.text(message));if(valid(s)&&s.player.getOpenInventory().getTopInventory()==s.inventory)show(s.player,s.session,s.facility,s.recipe,s.times,s.goal);
            },owner);
    }
    public boolean settled(UUID run){return actions.settled(run)&&(questBinding==null||questBinding.settled(run));}
    public CompletionStage<Void> barrier(UUID run){return (questBinding==null?CompletableFuture.<Void>completedFuture(null):questBinding.barrier(run)).thenComposeAsync(v->actions.barrier(run),owner);}
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}
    @EventHandler public void closed(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Screen s)screens.remove(e.getPlayer().getUniqueId(),s);}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){screens.remove(e.getPlayer().getUniqueId());}
    public void close(){main();closed=true;if(questBinding!=null)questBinding.close();actions.close();sessions.crafting((p,i)->false,(p,b)->false);HandlerList.unregisterAll(this);screens.clear();opening.clear();}
}
