package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.session.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import io.github.jesterportfolio.hearth.persistence.QuestStore;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 초기 검증한 콘텐츠만 사용하는 격리 어댑터. 일반 서버에는 활성화하지 않습니다. */
public final class PaperQuestAdapter implements Listener,AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperSessionAdapter players;
    private final PersonalQuest quests;
    private final QuestCatalog catalog;
    private final QuestDialogue dialogues;
    private final QuestJournal journal;
    private final Executor owner;
    private final Set<UUID> worldIds;
    private final NamespacedKey tag;
    // World/Entity 객체 대신 물리 식별자만 보관합니다. 개인 저장 소유권은 논리 진행 키입니다.
    private final Map<UUID,Map<String,UUID>> npcs=new HashMap<>();
    private record Projection(PlayerSessions.Session session,QuestJournal.View view){}
    private record Mark(UUID world,UUID entity,NpcQuestView.Marker marker){}
    private final Map<UUID,Projection> projections=new HashMap<>();
    private final Map<UUID,Map<String,Mark>> marks=new HashMap<>();
    private final PaperQuestUI ui;
    private final io.github.jesterportfolio.hearth.phone.PaperPhone phone;
    private io.github.jesterportfolio.hearth.story.PaperStoryAdapter story;
    public void story(io.github.jesterportfolio.hearth.story.PaperStoryAdapter adapter){story=adapter;ui.storyGuide(p->adapter.guide(p,null));if(phone!=null)phone.storyGuide(p->adapter.guide(p,null));}
    private QuestRules rules=QuestRules.empty();private QuestRewards rewards;private io.github.jesterportfolio.hearth.persistence.QuestCompletionStore completionStore;
    private final org.bukkit.scheduler.BukkitTask expiry;
    public void completion(QuestRules definitions,QuestRewards rewardService,io.github.jesterportfolio.hearth.persistence.QuestCompletionStore storage){
        rules=definitions;rewards=rewardService;completionStore=storage;quests.completed((ctx,id)->{if(rules.rule(id).reward()==null)return;var p=Bukkit.getPlayer(ctx.playerId());if(p==null)return;var s=players.current(p);if(s!=null&&s.context().storyRunId().equals(ctx.storyRunId()))claim(p,s,id);});
    }
    private final Set<UUID> loadingDialogues=new HashSet<>();
    private boolean closed;
    private boolean playthroughEnabled;
    public void playthrough(Playthrough content,io.github.jesterportfolio.hearth.story.SharedStory shared){playthroughEnabled=true;ui.playthrough(content,shared);if(phone!=null)phone.playthrough(content,shared);}
    public PaperQuestAdapter(JavaPlugin plugin,PaperSessionAdapter players,PersonalQuest quests,QuestCatalog catalog,QuestJournal journal,Executor owner,Set<UUID> worldIds) {
        this.plugin=plugin;this.players=players;this.quests=quests;this.catalog=catalog;this.journal=journal;dialogues=new QuestDialogue(catalog);
        for(var c:catalog.all())for(var goal:c.goals())if(goal.interaction()!=null&&goal.interaction().startsWith("block:")){var material=Material.matchMaterial(goal.interaction().split(":")[4]);if(material==null||!material.isBlock()||material.isAir())throw new IllegalArgumentException("Unknown objective block material");}
        this.owner=owner;this.worldIds=Set.copyOf(worldIds);tag=new NamespacedKey(plugin,"quest_demo_npc");
        ui=new PaperQuestUI(plugin,players,quests,catalog,journal,owner);
        phone=plugin.getConfig().getBoolean("phone-dialog-enabled",false)?new io.github.jesterportfolio.hearth.phone.PaperPhone(plugin,players,journal,catalog,owner):null;
        if(phone!=null){phone.report(this::openNpcFromQuest);phone.completion(this::phoneCompletion);}
        ui.npcNavigation(this::npcNavigation);
        ui.npcSelection((p,npc,quest,revision)->{var s=players.current(p);if(s==null)return;try{var t=dialogues.selection(p.getUniqueId(),s,npc,quest);command(p,dialogues.command(t,"select"),revision);}catch(RuntimeException invalid){unavailable(p,invalid);}});
        ui.questAction((p,c)->command(p,c));ui.npcDialogue(this::openNpcFromQuest);ui.npcContact(this::openNpcFromFact);
        ui.dialogueBusy(p->loadingDialogues.contains(p.getUniqueId()));ui.projection((p,v)->{var s=players.current(p);if(s!=null)quests.restore(s.context(),s,()->players.current(p)==s,v.states()).exceptionally(error->null);projections.put(p.getUniqueId(),new Projection(players.current(p),v));render(p);},this::clearVisual,this::render);
        expiry=Bukkit.getScheduler().runTaskTimer(plugin,()->quests.expire(catalog).exceptionally(error->{plugin.getLogger().warning("HEARTH_QUEST_EXPIRY_HOLD");return null;}),20,20);
        Bukkit.getPluginManager().registerEvents(this,plugin);players.questCommand(this::command);
        for(World w:Bukkit.getWorlds())ensure(w);
    }
    public void ensure(World w) {
        if(closed || !worldIds.contains(w.getUID()))return;
        for(var content:catalog.npcs())dialogueRegion(w,content);
        var tracked=npcs.computeIfAbsent(w.getUID(),id->new HashMap<>());
        for(var content:catalog.npcs()) {
            w.getChunkAt(chunk(content.x()),chunk(content.z()));
            List<Entity> existing=w.getEntities().stream().filter(e->content.npcId().equals(npcId(e))).toList();
            UUID current=tracked.get(content.npcId());
            if(current!=null && existing.size()==1 && existing.getFirst().getUniqueId().equals(current))continue;
            existing.forEach(Entity::remove);
            var npc=w.spawn(new Location(w,content.x(),content.y(),content.z()),Villager.class,v->{
                v.setAI(false);v.setGravity(false);v.setInvulnerable(true);v.setSilent(true);v.setCollidable(false);
                v.setPersistent(false);v.setVisibleByDefault(false);v.customName(Component.text(content.name()));v.setCustomNameVisible(true);
                v.getPersistentDataContainer().set(tag,PersistentDataType.STRING,content.npcId());
            });
            tracked.put(content.npcId(),npc.getUniqueId());
        }
    }
    private boolean npcPermitted(Player p,String npc){
        var session=players.current(p);if(session==null)return false;
        var c=catalog.npc(npc);return story==null||story.access().content(session.context().storyRunId(),c.x(),c.y(),c.z());
    }
    private void removeMark(Mark mark){Entity e=Bukkit.getEntity(mark.entity());if(e!=null)e.remove();}
    private void clearVisual(Player p){
        projections.remove(p.getUniqueId());var old=marks.remove(p.getUniqueId());if(old!=null)old.values().forEach(this::removeMark);
        for(var rows:npcs.values())for(UUID id:rows.values()){Entity e=Bukkit.getEntity(id);if(plugin.isEnabled()&&e!=null&&p.canSee(e))p.hideEntity(plugin,e);}
    }
    /** 기존 표시 주기에서 호출하며 DB를 조회하지 않습니다. */
    private boolean render(Player p){
        var data=projections.get(p.getUniqueId());var session=players.current(p);
        if(data==null){clearVisual(p);return true;}
        try{if(closed||session==null||session!=data.session())throw new IllegalStateException("STALE_NPC_VIEW");journal.requireView(session.context(),data.view());}
        catch(RuntimeException invalid){clearVisual(p);return false;}
        var own=marks.computeIfAbsent(p.getUniqueId(),id->new HashMap<>());
        for(var c:catalog.npcs()){
            Entity npc=entity(p.getWorld(),c.npcId());
            boolean visible=npc!=null&&npc.isValid()&&npcPermitted(p,c.npcId());
            if(npc!=null){if(visible&&!p.canSee(npc))p.showEntity(plugin,npc);else if(!visible&&p.canSee(npc))p.hideEntity(plugin,npc);}
            var marker=visible&&p.getLocation().distanceSquared(npc.getLocation())<=256?NpcQuestView.build(catalog,c.npcId(),data.view().states(),q->journal.questAllowed(session.context(),q.id())).marker():NpcQuestView.Marker.NONE;
            Mark old=own.get(c.npcId());Entity oldEntity=old==null?null:Bukkit.getEntity(old.entity());
            if(old!=null&&(marker!=old.marker()||oldEntity==null||!old.world().equals(p.getWorld().getUID()))){removeMark(old);own.remove(c.npcId());old=null;}
            if(marker!=NpcQuestView.Marker.NONE&&old==null){var display=markerEntity(p.getWorld(),npc.getLocation().add(0,2.2,0),marker);own.put(c.npcId(),new Mark(p.getWorld().getUID(),display.getUniqueId(),marker));p.showEntity(plugin,display);}
        }
        return true;
    }
    public static TextDisplay markerEntity(World world,Location location,NpcQuestView.Marker marker){
        if(marker==NpcQuestView.Marker.NONE)throw new IllegalArgumentException("Empty NPC marker");
        return world.spawn(location,TextDisplay.class,e->{e.setPersistent(false);e.setVisibleByDefault(false);e.setGravity(false);e.setBillboard(Display.Billboard.CENTER);e.setShadowed(true);e.text(Component.text(marker==NpcQuestView.Marker.REPORT_READY?"!":"?",marker==NpcQuestView.Marker.REPORT_READY?NamedTextColor.GREEN:NamedTextColor.YELLOW));});
    }
    private static int chunk(double coordinate){return (int)Math.floor(coordinate)>>4;}
    private static String regionId(QuestCatalog.Npc c){return c.npcId().equals("survey_guide")?"hearth_demo_dialogue":"hearth_demo_dialogue_"+c.npcId();}
    private void dialogueRegion(World w,QuestCatalog.Npc c) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)),"Missing WG manager");
        var point=BlockVector3.at(Math.floor(c.x()),Math.floor(c.y()),Math.floor(c.z()));var top=point.add(0,1,0);
        var existing=manager.getRegion(regionId(c));
        if(existing==null) {
            var region=new ProtectedCuboidRegion(regionId(c),point,top);region.setPriority(30);
            region.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);region.setFlag(Flags.INTERACT,StateFlag.State.ALLOW);manager.addRegion(region);
        } else if(!existing.getMinimumPoint().equals(point) || !existing.getMaximumPoint().equals(top)
                || existing.getPriority()!=30 || existing.getFlag(Flags.INTERACT)!=StateFlag.State.ALLOW
                || existing.getFlag(Flags.PASSTHROUGH)!=StateFlag.State.ALLOW)
            throw new IllegalStateException("Existing dialogue region mismatch; preserve original");
    }
    public boolean dialogueAllowed(World w,QuestContent c){return dialogueAllowed(w,catalog.npc(c.npcId()));}
    public boolean dialogueAllowed(World w,QuestCatalog.Npc c) {
        var manager=Objects.requireNonNull(WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)));
        return manager.getApplicableRegions(BlockVector3.at(c.x(),c.y(),c.z())).queryState(null,Flags.INTERACT)==StateFlag.State.ALLOW;
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent e){
        if(!worldIds.contains(e.getWorld().getUID()) || catalog.npcs().stream().noneMatch(c->e.getChunk().getX()==chunk(c.x()) && e.getChunk().getZ()==chunk(c.z())))return;
        UUID id=e.getWorld().getUID();int x=e.getChunk().getX(),z=e.getChunk().getZ();
        Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null && w.isChunkLoaded(x,z))ensure(w);},1);
    }
    private String npcId(Entity e){return e.getPersistentDataContainer().get(tag,PersistentDataType.STRING);}
    public int count(World w,String npc){return (int)w.getEntities().stream().filter(e->npc.equals(npcId(e))).count();}
    public boolean tracks(UUID world){return npcs.containsKey(world);}
    private Entity entity(World w,String npc){var map=npcs.get(w.getUID());UUID id=map==null?null:map.get(npc);return id==null?null:w.getEntity(id);}
    private boolean near(Player p,String npc,UUID id){Entity e=entity(p.getWorld(),npc);return e!=null && e.isValid() && e.getUniqueId().equals(id) && p.getLocation().distanceSquared(e.getLocation())<=16;}
    private boolean current(Player p,PlayerSessions.Session session,QuestDialogue.Ticket t){return talkAllowed(p,t.npc(),t.entity()) && dialogues.current(t) && players.current(p)==session && p.getWorld().getUID().equals(t.world()) && near(p,t.npc(),t.entity());}
    @EventHandler public void load(WorldLoadEvent e){UUID id=e.getWorld().getUID();Bukkit.getScheduler().runTaskLater(plugin,()->{World w=Bukkit.getWorld(id);if(w!=null)ensure(w);},1);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unload(WorldUnloadEvent e){UUID id=e.getWorld().getUID();npcs.remove(id);dialogues.clearWorld(id);for(var rows:marks.values())rows.entrySet().removeIf(entry->{if(!entry.getValue().world().equals(id))return false;removeMark(entry.getValue());return true;});}
    /** RPG 사망과 일반 사망이 같은 저장 경로를 사용하되 일반 행동 권한은 열지 않습니다. */
    public CompletionStage<Void> combatDeath(Player p,PlayerSessions.Session session){
        return quests.died(session.context(),session,()->players.prepared(p)==session,
            ()->players.current(p)==session,catalog);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(org.bukkit.event.entity.PlayerDeathEvent event){
        var p=event.getPlayer();var session=players.prepared(p);
        if(session!=null)combatDeath(p,session).exceptionally(error->{plugin.getLogger().warning("HEARTH_QUEST_DEATH_HOLD");return null;});
    }
    @EventHandler public void quit(PlayerQuitEvent e){dialogues.clear(e.getPlayer().getUniqueId());clearVisual(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(PlayerInteractAtEntityEvent e){
        String npc=npcId(e.getRightClicked());if(npc==null)return;
        e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND)return;
        conversation(e.getPlayer(),npc,e.getRightClicked().getUniqueId());
    }
    private boolean talkAllowed(Player p,String npc,UUID id){
        if(!npcPermitted(p,npc)||!near(p,npc,id))return false;
        Entity target=entity(p.getWorld(),npc);var local=com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(p);
        var platform=WorldGuard.getInstance().getPlatform();
        return platform.getSessionManager().hasBypass(local,BukkitAdapter.adapt(p.getWorld()))||platform.getRegionContainer().createQuery().testState(BukkitAdapter.adapt(target.getLocation()),local,Flags.INTERACT);
    }
    private void openNpcFromQuest(Player p,String quest){
        var session=players.current(p);if(session==null||!catalog.questIds().contains(quest)||!journal.questAllowed(session.context(),quest)){unavailable(p);return;}
        var c=catalog.quest(quest);Entity npc=entity(p.getWorld(),c.npcId());
        if(npc==null||!talkAllowed(p,c.npcId(),npc.getUniqueId())){p.sendMessage(Component.text("보고할 NPC 가까이에서 다시 선택해 주세요. 지역·상호작용 권한도 필요합니다."));return;}
        conversation(p,c.npcId(),npc.getUniqueId());
    }
    private void openNpcFromFact(Player p,String key){
        var session=players.current(p);if(session==null)return;
        journal.load(session.context(),session,()->players.current(p)==session).whenCompleteAsync((view,error)->{
            if(players.current(p)!=session)return;
            if(error!=null){unavailable(p);return;}
            try{journal.requireView(session.context(),view);var contact=NpcContacts.resolve(catalog,view,key).orElseThrow(()->new IllegalArgumentException("Unknown contact"));
                Entity npc=entity(p.getWorld(),contact.npc());if(npc==null||!talkAllowed(p,contact.npc(),npc.getUniqueId())){p.sendMessage(Component.text("관련 NPC 가까이에서 다시 선택해 주세요. 지역·상호작용 권한도 필요합니다."));return;}
                conversation(p,contact.npc(),npc.getUniqueId());
            }catch(RuntimeException invalid){unavailable(p);}
        },owner);
    }
    private void npcNavigation(Player p,String npc,String action,String topic){
        var session=players.current(p);if(session==null)return;
        try{
            var scope=dialogues.conversation(p.getUniqueId());if(scope.session()!=session||!scope.npc().equals(npc)||!p.getWorld().getUID().equals(scope.world())||!talkAllowed(p,npc,scope.entity()))throw new IllegalArgumentException("STALE_NPC_MENU");
            if(action.equals("npc-close")){dialogues.clear(p.getUniqueId());p.closeInventory();return;}
            if(action.equals("npc-topic")){command(p,dialogues.topicCommand(dialogues.topicSelection(p.getUniqueId(),session,npc,topic)));return;}
            if(action.equals("npc-read")){command(p,"/hearthtalk read "+npc+" "+scope.nonce());return;}
            conversation(p,npc,scope.entity(),false,action.equals("npc-topics-open")?"npc-topics":"npc");
        }catch(RuntimeException stale){unavailable(p,stale);}
    }
    private void conversation(Player p,String npc,UUID entity){conversation(p,npc,entity,true,"npc");}
    private void conversation(Player p,String npc,UUID entity,boolean interaction,String page){
        var session=players.current(p);
        if(closed || session==null || !talkAllowed(p,npc,entity) || loadingDialogues.contains(p.getUniqueId()))return;
        if(story!=null&&!story.access().content(session.context().storyRunId(),p.getX(),p.getY(),p.getZ()))return;
        var tickets=dialogues.options(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),entity,npc);
        var context=dialogues.conversation(p.getUniqueId());
        java.util.function.BooleanSupplier valid=()->!closed&&players.current(p)==session&&dialogues.current(context)&&p.getWorld().getUID().equals(context.world())&&talkAllowed(p,npc,entity);
        loadingDialogues.add(p.getUniqueId());
        (interaction?interactObjectives(p,"npc:"+npc,()->talkAllowed(p,npc,entity)).thenComposeAsync(v->journal.interactPlace(session.context(),session,valid,"npc:"+npc,p.getX(),p.getY(),p.getZ()),owner):CompletableFuture.<Void>completedFuture(null))
            .handleAsync((v,error)->{if(error!=null)plugin.getLogger().info("HEARTH_DISCOVERY_REJECTED");return null;},owner)
            .thenComposeAsync(v->{if(!valid.getAsBoolean()||tickets.stream().anyMatch(t->!current(p,session,t)))throw new IllegalStateException("STALE_DIALOGUE");return quests.listAfterPending(session.context(),session,valid);},owner).whenCompleteAsync((rows,error)->{
            loadingDialogues.remove(p.getUniqueId());
            if(!valid.getAsBoolean()||tickets.stream().anyMatch(t->!current(p,session,t)))return;
            if(error!=null){unavailable(p,error);return;}
            try {quests.requireListDisplay(session.context(),rows);
                dialogues.topicOptions(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),entity,npc,catalog.availableTopics(npc,rows,id->journal.questAllowed(session.context(),id)));
                ui.openNpc(p,session,npc,rows,page,valid);
                if(story!=null)story.guide(p,npc);
                if(playthroughEnabled)p.sendMessage(Component.text("[다음 플레이 안내]").clickEvent(ClickEvent.runCommand("/hearthjourney")));
            }catch(RuntimeException failure){unavailable(p,failure);}
        },owner);
    }
    public static List<Component> selectionMessages(QuestCatalog catalog,QuestDialogue dialogues,List<QuestDialogue.Ticket> tickets,Map<String,QuestStore.Saved> rows){
        var byQuest=new HashMap<String,QuestDialogue.Ticket>();tickets.forEach(t->byQuest.put(t.quest(),t));
        if(tickets.isEmpty())return List.of();
        var view=NpcQuestView.build(catalog,tickets.getFirst().npc(),rows,c->byQuest.containsKey(c.id()));
        return view.options().stream().map(option->(Component)Component.text("["+option.status()+"] "+option.title()).clickEvent(ClickEvent.runCommand(dialogues.command(byQuest.get(option.quest()),"select")))).toList();
    }
    public static List<Component> selectionMessages(QuestCatalog catalog,QuestDialogue dialogues,List<QuestDialogue.Ticket> tickets){
        return tickets.stream().map(t->(Component)Component.text("["+catalog.quest(t.quest()).title()+"]").clickEvent(ClickEvent.runCommand(dialogues.command(t,"select")))).toList();
    }
    /** 인증된 선택의 현재 상태만 표시합니다. 선택용 nonce를 수락/보고에 재사용하지 않습니다. */
    public static List<Component> dialogueMessages(QuestContent c,QuestStore.Saved saved,QuestDialogue dialogues,QuestDialogue.Ticket selected){
        if(!dialogues.current(selected) || !c.id().equals(selected.quest()))throw new IllegalArgumentException("Stale dialogue response");
        String text=switch(saved.phase()){case NOT_ACCEPTED->"offer";case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";case FAILED,SKIPPED->null;};
        Component message=Component.text(saved.phase()==QuestStore.Phase.ACTIVE||text==null?c.guidance(saved):c.text().get(text));
        var action=dialogueAction(c,saved,dialogues,selected);if(action.isPresent())message=message.append(Component.newline()).append(action.get());
        return List.of(message);
    }
    private record DialogueAction(String label,String command,String decline){Component component(){return Component.text(label).clickEvent(ClickEvent.runCommand(command));}}
    private static Optional<Component> dialogueAction(QuestContent c,QuestStore.Saved saved,QuestDialogue dialogues,QuestDialogue.Ticket selected){return dialogueRequest(c,saved,dialogues,selected).map(DialogueAction::component);}
    private static Optional<DialogueAction> dialogueRequest(QuestContent c,QuestStore.Saved saved,QuestDialogue dialogues,QuestDialogue.Ticket selected){
        if(!dialogues.current(selected)||!c.id().equals(selected.quest()))throw new IllegalArgumentException("STALE_DIALOGUE");
        if(saved.phase()==QuestStore.Phase.FAILED)return Optional.of(new DialogueAction("[퀘스트 재시작]","/hearthquest retry "+c.id(),null));
        if(saved.phase()==QuestStore.Phase.NOT_ACCEPTED&&c.startMode()==QuestContent.StartMode.NPC||saved.phase()==QuestStore.Phase.REPORT_READY){
            String action=saved.phase()==QuestStore.Phase.NOT_ACCEPTED?"accept":"report";var next=dialogues.action(selected,action);
            return Optional.of(new DialogueAction(c.text().get(action),dialogues.command(next,action),action.equals("accept")?dialogues.declineCommand(next):null));
        }
        return Optional.empty();
    }

    public static List<Component> topicMessages(QuestCatalog catalog,QuestDialogue dialogue,List<QuestDialogue.TopicTicket> topics){
        return topics.stream().map(t->(Component)Component.text("[질문] "+catalog.topic(t.npc(),t.topic()).label()).clickEvent(ClickEvent.runCommand(dialogue.topicCommand(t)))).toList();
    }
    private boolean currentTopic(Player p,PlayerSessions.Session session,QuestDialogue.TopicTicket ticket){
        return players.current(p)==session&&dialogues.current(ticket)&&p.getWorld().getUID().equals(ticket.world())&&talkAllowed(p,ticket.npc(),ticket.entity());
    }
    private void ask(Player p,String command){
        var session=players.current(p);if(session==null)return;final QuestDialogue.TopicTicket ticket;
        try{String[] parts=command.split(" ",-1);if(parts.length!=5||!parts[2].matches("[a-z][a-z0-9_]{0,63}")||!parts[3].matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid topic request");
            var npc=entity(p.getWorld(),parts[2]);if(npc==null||!talkAllowed(p,parts[2],npc.getUniqueId()))throw new IllegalArgumentException("NPC unavailable");
            var nonce=UUID.fromString(parts[4]);if(!nonce.toString().equals(parts[4]))throw new IllegalArgumentException("Invalid nonce");
            ticket=dialogues.authorizeTopic(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),npc.getUniqueId(),parts[2],parts[3],nonce);
        }catch(IllegalArgumentException e){p.sendMessage(Component.text("현재 유효한 질문이 아닙니다. 가까이에서 다시 대화해 주세요."));return;}
        journal.topic(session.context(),session,()->currentTopic(p,session,ticket),ticket.npc(),ticket.topic()).whenCompleteAsync((view,error)->{
            if(!currentTopic(p,session,ticket))return;
            if(error!=null){unavailable(p);return;}
            try{journal.requireView(session.context(),view);var topic=catalog.topic(ticket.npc(),ticket.topic());if(!topic.available(view.states(),id->journal.questAllowed(session.context(),id)))throw new IllegalStateException("TOPIC_LOCKED");
                var actions=new ArrayList<Component>();actions.add(Component.text("[NPC 선택창]").clickEvent(ClickEvent.runCommand(dialogues.menuCommand(dialogues.conversation(p.getUniqueId())))));
                actions.add(Component.text("[대화 닫기]").clickEvent(ClickEvent.runCommand(dialogues.endCommand(dialogues.conversation(p.getUniqueId())))));
                if(topic.journal()!=null)actions.add(Component.text("[개인 일지 확인]").clickEvent(ClickEvent.runCommand("/hearthphone diary")));
                ui.showBook(p,session,new QuestBookView(topic.label(),QuestBookView.paginate(List.of(catalog.npc(ticket.npc()).name(),topic.text()))),actions);
            }catch(RuntimeException failure){unavailable(p);}
        },owner);
    }
    private void pageBook(Player p,PlayerSessions.Session session,QuestDialogue.Page page){
        var actions=new ArrayList<Component>();
        if(page.index()>0)actions.add(Component.text("[이전 대화]").clickEvent(ClickEvent.runCommand(dialogues.pageCommand(page,"back"))));
        if(page.index()+1<catalog.pages(page.npc()).size())actions.add(Component.text("[다음 대화]").clickEvent(ClickEvent.runCommand(dialogues.pageCommand(page,"next"))));
        actions.add(Component.text("[NPC 선택창]").clickEvent(ClickEvent.runCommand(dialogues.menuCommand(dialogues.conversation(p.getUniqueId())))));
        actions.add(Component.text("[대화 닫기]").clickEvent(ClickEvent.runCommand(dialogues.pageCommand(page,"close"))));
        ui.showBook(p,session,new QuestBookView(catalog.npc(page.npc()).name(),QuestBookView.paginate(List.of("대화 "+(page.index()+1)+" / "+catalog.pages(page.npc()).size(),catalog.pages(page.npc()).get(page.index())))),actions);
    }
    public static List<Component> pageMessages(QuestCatalog catalog,QuestDialogue dialogue,QuestDialogue.Page page){
        if(!dialogue.current(page))throw new IllegalArgumentException("Stale dialogue page");var texts=catalog.pages(page.npc());var out=new ArrayList<Component>();
        out.add(Component.text("NPC 대화 · "+(page.index()+1)+"/"+texts.size()));out.add(Component.text(texts.get(page.index())));
        if(page.index()>0)out.add(Component.text("[이전 대화]").clickEvent(ClickEvent.runCommand(dialogue.pageCommand(page,"back"))));
        if(page.index()+1<texts.size())out.add(Component.text("[다음 대화]").clickEvent(ClickEvent.runCommand(dialogue.pageCommand(page,"next"))));
        out.add(Component.text("[대화 닫기]").clickEvent(ClickEvent.runCommand(dialogue.pageCommand(page,"close"))));return List.copyOf(out);
    }
    private CompletionStage<Void> phoneCompletion(Player p,PlayerSessions.Session session,io.github.jesterportfolio.hearth.phone.PhoneCompletion.Choice choice){
        java.util.function.BooleanSupplier valid=()->!closed&&players.current(p)==session;
        if(!valid.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("STALE_PHONE_ACTION"));
        return journal.load(session.context(),session,valid).thenComposeAsync(view->{
            if(!valid.getAsBoolean())throw new IllegalStateException("STALE_PHONE_ACTION");journal.requireView(session.context(),view);
            io.github.jesterportfolio.hearth.phone.PhoneCompletion.require(catalog,view,choice);
            return switch(choice.operation()){
                case CLAIM->{if(rewards==null)yield CompletableFuture.failedFuture(new IllegalStateException("REWARDS_DISABLED"));yield players.reward(session,rewards,choice.id());}
                case RETRY->quests.open(session.context(),choice.id(),session,valid).thenComposeAsync(q->{if(!valid.getAsBoolean()||!journal.questAllowed(session.context(),choice.id()))throw new IllegalStateException("STALE_PHONE_ACTION");return quests.retry(q,catalog.quest(choice.id()));},owner).thenApply(v->(Void)null);
                case SKIP->{if(completionStore==null||!rules.chapters().containsKey(choice.id()))yield CompletableFuture.failedFuture(new IllegalStateException("CHAPTER_DISABLED"));yield quests.skip(session.context(),session,valid,completionStore,rules.chapters().get(choice.id())).thenApply(v->(Void)null);}
            };
        },owner);
    }
    private void claim(Player p,PlayerSessions.Session session,String quest){
        if(rewards==null||!catalog.questIds().contains(quest)||!journal.questAllowed(session.context(),quest)){unavailable(p);return;}
        players.reward(session,rewards,quest).whenCompleteAsync((v,error)->{if(error!=null){plugin.getLogger().warning("HEARTH_QUEST_REWARD "+io.github.jesterportfolio.hearth.inventory.InventorySaveSession.code(error));if(p.isOnline())p.sendMessage(Component.text("보상을 지급하지 못했습니다. 공간·저장 상태를 확인해 주세요. 미등록/스킵 보상은 지급하지 않습니다."));}else if(players.current(p)==session)p.sendMessage(Component.text("퀘스트 보상 지급 상태를 DB에서 확인했습니다. 중복 지급하지 않습니다."));},owner);
    }
    private void chapter(Player p,PlayerSessions.Session session,String action,String id){
        if(completionStore==null||story==null){unavailable(p);return;}var definition=id==null?null:rules.chapters().get(id);
        if(action.equals("skip")){
            if(definition==null){unavailable(p);return;}
            quests.skip(session.context(),session,()->players.current(p)==session,completionStore,definition).whenCompleteAsync((row,error)->{if(players.current(p)!=session)return;if(error!=null){unavailable(p);return;}p.sendMessage(Component.text("선택한 장의 메인/필수 길목만 스킵했습니다. 실제 완료·보상과 구분하며 공동체 결과는 유지합니다."));},owner);return;
        }
        journal.load(session.context(),session,()->players.current(p)==session).thenComposeAsync(view->completionStore.readChapters(session.context()).thenApply(saved->Map.entry(view,saved)),owner).whenCompleteAsync((result,error)->{
            if(players.current(p)!=session)return;if(error!=null){unavailable(p);return;}
            try{journal.requireView(session.context(),result.getKey());var shared=story.shared().cached(session.context().storyRunId());
                var available=rules.chapters().values().stream().filter(c->c.selected(shared).isPresent()).sorted(Comparator.comparing(QuestRules.Chapter::id)).toList();
                if(available.isEmpty()){p.sendMessage(Component.text("공동체가 지나온 따라잡기 대상 장이 없습니다."));return;}
                if(action.equals("play")){if(definition==null||!available.contains(definition))throw new IllegalArgumentException("Unknown chapter");p.sendMessage(Component.text("직접 플레이: "+definition.title()+" · 기존 NPC/지역 시작 조건과 공동체 확정 분기를 따릅니다. 과거 세계 상태를 되돌리지 않습니다."));for(String quest:definition.selected(shared).orElseThrow())if(journal.questAllowed(session.context(),quest)&&QuestListView.available(catalog.quest(quest),result.getKey().states()))p.sendMessage(Component.text(catalog.quest(quest).title()+" · "+catalog.quest(quest).name()).clickEvent(ClickEvent.runCommand("/hearthquest info "+quest)));return;}
                for(var c:available)p.sendMessage(Component.text(c.title()+" · "+(result.getValue().containsKey(c.id())?"스킵 기록 있음":"개인 진행 선택")).append(Component.text(" [직접 플레이]").clickEvent(ClickEvent.runCommand("/hearthquest chapter-play "+c.id()))).append(Component.text(" [장 스킵]").clickEvent(ClickEvent.runCommand("/hearthquest chapter-skip "+c.id()))));
            }catch(RuntimeException invalid){unavailable(p);}
        },owner);
    }
    private boolean command(Player p,String command){return command(p,command,null);}
    private boolean command(Player p,String command,Long guiRevision){
        if(command.startsWith("/hearthtalk ask ")){ask(p,command);return true;}
        if(command.equals("/hearthtalk")||command.startsWith("/hearthtalk ")){
            var session=players.current(p);if(session==null)return true;
            try{String[] parts=command.split(" ",-1);if(parts.length!=4||!Set.of("next","back","close","menu","read","end").contains(parts[1])||!parts[2].matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid page request");
                Entity npc=entity(p.getWorld(),parts[2]);if(npc==null||!talkAllowed(p,parts[2],npc.getUniqueId()))throw new IllegalArgumentException("NPC unavailable");
                UUID nonce=UUID.fromString(parts[3]);if(!nonce.toString().equals(parts[3]))throw new IllegalArgumentException("Invalid nonce");
                if(Set.of("menu","read","end").contains(parts[1])){
                    dialogues.authorizeMenu(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),npc.getUniqueId(),parts[2],nonce);
                    if(parts[1].equals("end")){dialogues.clear(p.getUniqueId());p.closeInventory();}
                    else if(parts[1].equals("menu"))conversation(p,parts[2],npc.getUniqueId(),false,"npc");else dialogues.beginPages(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),npc.getUniqueId(),parts[2]).ifPresent(page->pageBook(p,session,page));
                }else{var page=dialogues.turn(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),npc.getUniqueId(),parts[2],nonce,parts[1]);if(page.isPresent())pageBook(p,session,page.get());else p.closeInventory();}
            }catch(RuntimeException error){p.sendMessage(Component.text("현재 유효한 NPC 대화가 아닙니다. 가까이에서 다시 대화해 주세요."));}return true;
        }
        if(story!=null&&story.command(p,command))return true;
        if(phone!=null&&players.current(p)!=null&&phone.command(p,command))return true;
        if(command.equals("/hearthphone legacy"))return ui.command(p,"/hearthphone");
        if(ui.command(p,command))return true;
        if(!command.startsWith("/hearthquest"))return false;
        var session=players.current(p);if(session==null)return true;
        if(command.equals("/hearthquest chapters")){chapter(p,session,"list",null);return true;}
        if(command.startsWith("/hearthquest retry ")||command.startsWith("/hearthquest claim ")||command.startsWith("/hearthquest chapter-play ")||command.startsWith("/hearthquest chapter-skip ")){
            var parts=command.split(" ",-1);if(parts.length!=3||!parts[2].matches("[a-z][a-z0-9_]{0,63}")){unavailable(p);return true;}
            if(parts[1].startsWith("chapter-")){chapter(p,session,parts[1].equals("chapter-skip")?"skip":"play",parts[2]);return true;}
            if(!catalog.questIds().contains(parts[2])||!journal.questAllowed(session.context(),parts[2])){unavailable(p);return true;}
            if(parts[1].equals("claim")){claim(p,session,parts[2]);return true;}
            quests.open(session.context(),parts[2],session,()->players.current(p)==session).thenComposeAsync(q->quests.retry(q,catalog.quest(parts[2])),owner).whenCompleteAsync((saved,error)->{if(players.current(p)!=session)return;if(error!=null)unavailable(p);else p.sendMessage(Component.text("퀘스트를 재시작했습니다. "+catalog.quest(parts[2]).guidance(saved)));},owner);return true;
        }
        if(command.equals("/hearthquest list")){read(p,session,null);return true;}
        if(command.equals("/hearthquest info") || command.startsWith("/hearthquest info ")) {
            var id=QuestListView.infoId(command);
            if(id.isEmpty())p.sendMessage(Component.text(QuestListView.UNAVAILABLE));else read(p,session,id.get());
            return true;
        }
        final QuestDialogue.Request request;final QuestDialogue.Ticket ticket;
        try {
            request=QuestDialogue.Request.parse(command);
            Entity e=entity(p.getWorld(),request.npc());
            if(e==null || !near(p,request.npc(),e.getUniqueId()))throw new IllegalArgumentException("NPC unavailable");
            if(request.action().equals("decline")){
                if(!talkAllowed(p,request.npc(),e.getUniqueId()))throw new IllegalArgumentException("NPC unavailable");
                dialogues.decline(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),e.getUniqueId(),request);
                conversation(p,request.npc(),e.getUniqueId(),false,"npc");return true;
            }
            ticket=dialogues.authorize(p.getUniqueId(),session,session.context().storyRunId(),p.getWorld().getUID(),e.getUniqueId(),request);
        } catch(IllegalArgumentException error){plugin.getLogger().info("HEARTH_QUEST_REQUEST_REJECTED");return true;}
        QuestContent content=catalog.quest(ticket.quest());
        if(!journal.questAllowed(session.context(),content.id())){unavailable(p);return true;}
        if(request.action().equals("select")) {
            quests.listAfterPending(session.context(),session,()->players.current(p)==session).thenComposeAsync(rows->{
                if(!current(p,session,ticket))throw new IllegalStateException("STALE_DIALOGUE");
                quests.requireListDisplay(session.context(),rows);if(!QuestListView.available(content,rows))throw new IllegalStateException("QUEST_LOCKED");
                return quests.open(session.context(),content.id(),session,()->players.current(p)==session);
            },owner).whenCompleteAsync((q,error)->{
                if(!current(p,session,ticket))return;
                if(error!=null||!journal.questAllowed(session.context(),content.id())){unavailable(p);return;}
                try {var saved=quests.requireDialogue(q);
                    if(guiRevision!=null){
                        if(saved.revision()!=guiRevision||saved.phase()!=QuestStore.Phase.NOT_ACCEPTED&&saved.phase()!=QuestStore.Phase.REPORT_READY)throw new IllegalStateException("STALE_GUI_ACTION");
                        var action=dialogueRequest(content,saved,dialogues,ticket).orElseThrow(()->new IllegalStateException("QUEST_ACTION_UNAVAILABLE"));command(p,action.command());
                    }else{
                        var actions=new ArrayList<Component>();var action=dialogueRequest(content,saved,dialogues,ticket);
                        action.ifPresent(a->{actions.add(a.component());if(a.decline()!=null)actions.add(Component.text("[거절]").clickEvent(ClickEvent.runCommand(a.decline())));});
                        actions.add(Component.text("[NPC 선택창]").clickEvent(ClickEvent.runCommand(dialogues.menuCommand(dialogues.conversation(p.getUniqueId())))));
                        ui.showBook(p,session,QuestBookView.dialogue(content,saved),actions);
                    }
                }
                catch(RuntimeException invalid){unavailable(p);}
            },owner);
        } else quests.open(session.context(),content.id(),session,()->players.current(p)==session)
            .thenComposeAsync(q->{if(!current(p,session,ticket)||!journal.questAllowed(session.context(),content.id()))throw new IllegalStateException("STALE_DIALOGUE");return request.action().equals("accept")?quests.accept(q,content):quests.advance(q,QuestStore.Phase.COMPLETED);},owner)
            .whenCompleteAsync((saved,error)->{
                if(!current(p,session,ticket))return;
                if(error!=null||!journal.questAllowed(session.context(),content.id())){unavailable(p);return;}
                p.sendMessage(Component.text(content.text().get(saved.phase()==QuestStore.Phase.ACTIVE?"active":"completed")));
                p.sendMessage(Component.text("[퀘스트 목록]").clickEvent(ClickEvent.runCommand("/hearthquest list")).append(Component.text(" [스마트폰]").clickEvent(ClickEvent.runCommand("/hearthphone"))));
                if(story!=null&&saved.phase()==QuestStore.Phase.COMPLETED)story.guide(p,ticket.npc());
                conversation(p,ticket.npc(),ticket.entity(),false,"npc");
            },owner);
        return true;
    }
    public static List<Component> listMessages(QuestCatalog catalog,Map<String,QuestStore.Saved> states) {
        var rows=QuestListView.entries(catalog,states);
        if(rows.isEmpty())return List.of(Component.text(QuestListView.lines(rows).getFirst()));
        return rows.stream().map(row->(Component)Component.text("["+row.status()+"] "+row.title()).clickEvent(ClickEvent.runCommand(QuestListView.infoCommand(row)))).toList();
    }
    public static List<Component> infoMessages(QuestCatalog catalog,Map<String,QuestStore.Saved> states,String id) {
        return QuestListView.detailLines(QuestListView.detail(catalog,states,id)).stream().map(line->(Component)Component.text(line)).toList();
    }
    private void read(Player p,PlayerSessions.Session session,String questId) {
        quests.list(session.context(),session,()->players.current(p)==session).whenCompleteAsync((states,error)->{
            if(players.current(p)!=session)return;
            if(error!=null){p.sendMessage(Component.text("퀘스트 정보를 확인할 수 없습니다. 저장 처리 또는 오류 상태를 확인해 주세요."));plugin.getLogger().info("HEARTH_QUEST_READ_REJECTED");return;}
            try {quests.requireListDisplay(session.context(),states);if(questId==null){var entries=QuestListView.entries(catalog,states).stream().filter(q->journal.questAllowed(session.context(),q.questId())).toList();if(entries.isEmpty())p.sendMessage(Component.text("수락한 퀘스트가 없습니다"));else for(var row:entries)p.sendMessage(Component.text("["+row.status()+"] "+row.title()).clickEvent(ClickEvent.runCommand(QuestListView.infoCommand(row))));}
                else for(Component message:journal.blocked(session.context()).contains(questId)?List.of(Component.text(QuestListView.UNAVAILABLE)):infoMessages(catalog,states,questId))p.sendMessage(message);}
            catch(RuntimeException failure){p.sendMessage(Component.text("퀘스트 정보를 확인할 수 없습니다."));plugin.getLogger().warning("HEARTH_QUEST_READ_INVALID_RESPONSE");}
        },owner);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(e instanceof PlayerTeleportEvent || e.getTo()==null)return;
        var p=e.getPlayer();var session=players.current(p);var to=e.getTo();
        if(session==null || !worldIds.contains(p.getWorld().getUID()))return;
        var from=e.getFrom();
        journal.enterPlaces(session.context(),session,()->players.current(p)==session,from.getX(),from.getY(),from.getZ(),to.getX(),to.getY(),to.getZ()).exceptionally(error->{plugin.getLogger().info("HEARTH_DISCOVERY_REJECTED");return null;});
        for(QuestContent c:catalog.all())if(journal.questAllowed(session.context(),c.id()))quests.enter(session.context(),c,session,()->players.current(p)==session,
            from.getX(),from.getY(),from.getZ(),to.getX(),to.getY(),to.getZ()).thenAcceptAsync(saved->{
                if(players.current(p)!=session || saved.isEmpty()||!journal.questAllowed(session.context(),c.id()))return;
                String key=switch(saved.get().phase()){case ACTIVE->"active";case REPORT_READY->"report-ready";case COMPLETED->"completed";default->throw new IllegalStateException("Unexpected movement result");};
                p.sendMessage(Component.text(c.guidance(saved.get())));
            },owner).exceptionally(error->null);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void object(org.bukkit.event.player.PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        var p=e.getPlayer();var session=players.current(p);var block=e.getClickedBlock();
        if(session==null || !worldIds.contains(p.getWorld().getUID()) || p.getLocation().distanceSquared(block.getLocation())>36)return;
        String reference="block:"+block.getX()+":"+block.getY()+":"+block.getZ()+":"+block.getType().name();UUID world=block.getWorld().getUID();
        interactObjectives(p,reference,()->p.getWorld().getUID().equals(world)&&p.getLocation().distanceSquared(block.getLocation())<=36&&reference.endsWith(":"+block.getType().name())&&blockAllowed(p,block)).thenComposeAsync(v->journal.interactPlace(session.context(),session,()->players.current(p)==session,reference,p.getX(),p.getY(),p.getZ()),owner).exceptionally(error->{plugin.getLogger().info("HEARTH_DISCOVERY_REJECTED");return null;});
    }
    private boolean blockAllowed(Player p,org.bukkit.block.Block block){
        var local=com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(p);var platform=WorldGuard.getInstance().getPlatform();
        return platform.getSessionManager().hasBypass(local,BukkitAdapter.adapt(p.getWorld()))||platform.getRegionContainer().createQuery().testState(BukkitAdapter.adapt(block.getLocation()),local,Flags.INTERACT);
    }
    private CompletionStage<Void> interactObjectives(Player p,String reference,java.util.function.BooleanSupplier permitted){
        var session=players.current(p);if(session==null)return CompletableFuture.completedFuture(null);CompletionStage<Void> work=CompletableFuture.completedFuture(null);
        for(var c:catalog.all())if(c.goals().stream().anyMatch(g->reference.equals(g.interaction()))&&journal.questAllowed(session.context(),c.id()))work=work.thenComposeAsync(v->{
            if(players.current(p)!=session||!permitted.getAsBoolean())throw new IllegalStateException("STALE_INTERACTION");
            return quests.open(session.context(),c.id(),session,()->players.current(p)==session).thenComposeAsync(q->{
                if(!permitted.getAsBoolean()||!journal.questAllowed(session.context(),c.id()))throw new IllegalStateException("STALE_INTERACTION");
                var saved=quests.requireDialogue(q);if(saved.phase()!=QuestStore.Phase.ACTIVE||!reference.equals(c.goal(saved).interaction()))return CompletableFuture.completedFuture(null);
                return quests.interact(q,c,reference).thenAcceptAsync(row->{if(players.current(p)==session&&permitted.getAsBoolean())p.sendMessage(Component.text(c.guidance(row)));},owner);
            },owner);
        },owner);return work;
    }
    private void unavailable(Player p,Throwable error){
        Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
        String code=Objects.toString(cause.getMessage(),"");
        plugin.getLogger().warning("HEARTH_NPC_DIALOGUE_REJECTED "+cause.getClass().getSimpleName()+" "+(code.matches("[A-Z][A-Z0-9_]{0,63}")?code:"DETAIL_REDACTED"));
        unavailable(p);
    }
    private void unavailable(Player p){p.sendMessage(Component.text("퀘스트 정보를 확인할 수 없습니다."));plugin.getLogger().info("HEARTH_QUEST_REQUEST_UNAVAILABLE");}
    private void failure(Player p,QuestContent content){p.sendMessage(Component.text(content.text().get("failure")));plugin.getLogger().warning("HEARTH_QUEST_ACTION_REJECTED");}
    public void stopUi(){ui.close();}
    @Override public void close(){if(closed)return;closed=true;expiry.cancel();quests.completed((ctx,id)->{});try{io.github.jesterportfolio.hearth.util.ShutdownCleanup.close(phone,ui);}finally{HandlerList.unregisterAll(this);players.questCommand((p,c)->false);dialogues.clear();
        for(UUID id:worldIds){World w=Bukkit.getWorld(id);if(w!=null)w.getEntities().stream().filter(e->npcId(e)!=null).forEach(Entity::remove);}npcs.clear();marks.values().forEach(rows->rows.values().forEach(this::removeMark));marks.clear();projections.clear();loadingDialogues.clear();}}
}
