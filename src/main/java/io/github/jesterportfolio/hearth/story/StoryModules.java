package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.combat.*;
import io.github.jesterportfolio.hearth.crafting.*;
import io.github.jesterportfolio.hearth.gathering.*;
import io.github.jesterportfolio.hearth.escort.*;
import io.github.jesterportfolio.hearth.travel.*;
import io.github.jesterportfolio.hearth.quest.*;
import io.github.jesterportfolio.hearth.exploration.*;
import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.inventory.*;
import io.github.jesterportfolio.hearth.session.*;
import io.github.jesterportfolio.hearth.lifecycle.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 정상 콘텐츠의 실제 시설/전투/퀘스트 흐름. fixture 설치와 플레이 대체 코드를 포함하지 않습니다. */
public final class StoryModules implements AutoCloseable {
    public final StoryModuleContent content;public final PaperCrafting crafting;public final PaperGathering gathering;public final PaperTravel travel;public final PaperEscort escort;
    public final PaperCombatTrials trials;public final PaperFieldEncounters fields;public final PaperCombatPlayers combat;public final PaperDungeonPlay dungeon;
    private final StoryContentBundle bundle;private final PaperSessionAdapter players;private final PlayerSessions sessions;private final QuestJournal journal;private final PersonalQuest personal;private final SharedStory shared;private final Map<UUID,WorldLifecycle> worlds;private final Executor owner;private final PaperInventoryCodec codec;private boolean stopped,closed;
    public StoryModules(JavaPlugin plugin,StoryContentBundle bundle,PostgresProgressStore database,ServerProgressAccess access,PaperSessionAdapter players,PlayerSessions sessions,QuestJournal journal,PersonalQuest quests,SharedStory shared,PaperQuestAdapter ui,Map<UUID,WorldLifecycle> worlds,PaperInventoryCodec codec,Executor owner){
        this.bundle=bundle;content=bundle.modules();this.players=players;this.sessions=sessions;this.journal=journal;this.personal=quests;this.shared=shared;this.worlds=Map.copyOf(worlds);this.codec=codec;this.owner=owner;
        trials=content.combat()==null?null:new PaperCombatTrials(plugin,content.combat(),owner,()->System.nanoTime()/1_000_000);
        fields=content.field()==null?null:new PaperFieldEncounters(plugin,players,worlds,content.field());
        combat=trials==null?null:new PaperCombatPlayers(plugin,players,trials,fields,database,database,worlds,owner,ui::combatDeath,this::dataBarrier);
        crafting=content.crafting()==null?null:new PaperCrafting(plugin,players,worlds,content.crafting(),codec,(context,facility)->new StoryAccess(shared).content(context.storyRunId(),facility.x(),facility.y(),facility.z()),owner);
        if(crafting!=null){var binding=new CraftingQuest(quests,bundle.quests(),crafting.content,owner,StoryModules::main);crafting.quests(binding,journal::questAllowed);crafting.learning(ui::showLearning);crafting.journey(ui::showJourney);crafting.orders((player,facility,current)->{var session=Objects.requireNonNull(players.current(player));return journal.load(session.context(),session,()->players.current(player)==session&&current.getAsBoolean()).thenApplyAsync(view->{if(players.current(player)!=session||!current.getAsBoolean())throw new IllegalStateException("STORY_CRAFT_ORDER_STALE");journal.requireView(session.context(),view);return CraftingOrders.build(bundle.quests(),crafting.content,facility,view);},owner);},ui::showQuest);}
        gathering=content.gathering()==null?null:new PaperGathering(plugin,database,access,players,worlds,content.gathering(),owner,System::currentTimeMillis,this::saveBoundary);
        travel=content.travel()==null?null:new PaperTravel(plugin,players,journal,worlds,content.travel(),owner);
        escort=content.escort()==null?null:new PaperEscort(plugin,players,journal,quests,bundle.quests(),worlds,content.escort(),owner);if(escort!=null)ui.escort(content.escort().quests().keySet(),escort::command,escort::show);
        dungeon=content.dungeon()==null?null:new PaperDungeonPlay(plugin,content.dungeon(),bundle.quests(),quests,journal,players,combat,content.combat(),worlds,owner,this::saveBoundary);
        if(dungeon!=null){ui.dungeon(content.dungeon().npc(),content.dungeon().quest(),dungeon::enter,dungeon::guide);ui.dungeonObjectives((player,view)->{var session=Objects.requireNonNull(players.current(player));journal.requireView(session.context(),view);return FacilityObjectives.dungeon(bundle.quests(),view,content.dungeon(),dungeon.information(player));});}
        if(content.facilities()!=null)gathering.facilities(content.facilities(),(context,site)->new StoryAccess(shared).content(context.storyRunId(),site.x(),site.y(),site.z()),ui::showJourney,(player,current,back)->{var session=Objects.requireNonNull(players.current(player));ui.showFacility(player,view->{if(players.current(player)!=session||!current.getAsBoolean())throw new IllegalStateException("STORY_GATHERING_GUIDE_STALE");journal.requireView(session.context(),view);return FacilityObjectives.gathering(bundle.quests(),view,content.gathering(),content.gatheringQuests(),gathering.information(player));},()->players.current(player)==session&&current.getAsBoolean(),back);});
        if(content.playthrough()!=null)ui.playthrough(content.playthrough(),shared);
        if(content.preparation()!=null){crafting.preparationGuide(ui::showPreparation);ui.preparation(this::preparation);}
        if(content.route()!=null)ui.courseRoute((player,view)->{var session=Objects.requireNonNull(players.current(player));var page=preparation(player,view);return content.route().build(content.playthrough(),bundle.quests(),view,shared.cached(session.context().storyRunId()),shared.phase(session.context().storyRunId()),page);});
        players.travelRestriction(p->{var session=players.prepared(p);return session!=null&&(fields==null||fields.entities(session.context().storyRunId()).stream().filter(e->e instanceof org.bukkit.entity.Mob).map(e->(org.bukkit.entity.Mob)e).noneMatch(e->e.getTarget()==p));});
    }
    private static void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("STORY_MODULE_OWNER");}
    public CompletionStage<Void> prepare(ServerProgressAccess.Context context){main();if(stopped)return CompletableFuture.failedFuture(new IllegalStateException("STORY_MODULES_CLOSED"));return gathering==null?CompletableFuture.completedFuture(null):gathering.prepare(context);}
    public void features(S4Features features){main();features.beforeReady((p,session)->prepare(session.context()));}
    private CompletionStage<Void> dataBarrier(UUID run){return personal.barrier(run).thenComposeAsync(v->journal.barrier(run),owner).thenComposeAsync(v->shared.barrier(run),owner);}
    private CompletionStage<Void> saveBoundary(UUID run){return sessions.saveRunBoundary(run).thenComposeAsync(v->dataBarrier(run),owner);}
    private PreparationGuide.Page preparation(Player player,QuestJournal.View view){
        main();var session=Objects.requireNonNull(players.current(player));journal.requireView(session.context(),view);var inventory=codec.capture(player.getInventory());var materials=new HashMap<String,List<CraftingMaterials.Need>>();var plans=new HashMap<String,CraftingPreparation.Plan>();
        for(var entry:content.preparation().entries())if(entry.kind()==PreparationGuide.Kind.CRAFTING&&view.exploration().place(entry.place()).isPresent()){
            for(String recipe:entry.recipes())materials.put(recipe,crafting.planner.materials(crafting.content.recipes().get(recipe),inventory));
            plans.put(entry.id(),crafting.planner.preparation(Objects.requireNonNull(Bukkit.getWorld(worlds.get(session.context().storyRunId()).mapping().physicalId())),crafting.content,crafting.content.facilities().get(entry.reference()),entry.recipes().stream().map(id->new CraftingBatch.Selection(id,1)).toList(),inventory));
        }
        return content.preparation().build(bundle.quests(),crafting.content,view,new PreparationGuide.Facts(materials,gathering.information(player),dungeon.information(player),content.dungeon().entry().contains(player.getX(),player.getY(),player.getZ()),session.save().dirty(),session.save().saving(),plans));
    }
    public void restored(UUID run){main();if(crafting!=null)crafting.ensureRegions(run);if(gathering!=null)gathering.ensureFacilities(run);if(travel!=null)travel.ensureRegions(run);}
    public CompletionStage<Void> barrier(UUID run){
        CompletionStage<Void> work=crafting==null?CompletableFuture.completedFuture(null):crafting.barrier(run);
        return work.thenComposeAsync(v->escort==null?CompletableFuture.completedFuture(null):escort.barrier(run),owner).thenComposeAsync(v->dungeon==null?CompletableFuture.completedFuture(null):dungeon.barrier(run),owner).thenComposeAsync(v->gathering==null?CompletableFuture.completedFuture(null):gathering.drain(run),owner).thenComposeAsync(v->travel==null?CompletableFuture.completedFuture(null):travel.barrier(run),owner).thenComposeAsync(v->combat==null?CompletableFuture.completedFuture(null):combat.barrier(run),owner);
    }
    public boolean settled(UUID run){main();return (crafting==null||crafting.settled(run))&&(gathering==null||gathering.settled(run))&&(travel==null||travel.settled(run));}
    public void unloaded(UUID run){main();if(dungeon!=null)dungeon.unloaded(run);if(gathering!=null)gathering.unloaded(run);}
    public void stopActive(){main();if(stopped)return;stopped=true;io.github.jesterportfolio.hearth.util.ShutdownCleanup.close(escort,dungeon,gathering,travel,combat,trials,fields);}
    @Override public void close(){main();if(closed)return;closed=true;try{stopActive();}finally{if(crafting!=null)crafting.close();}}
}
