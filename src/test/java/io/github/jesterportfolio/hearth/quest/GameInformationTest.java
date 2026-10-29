package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.exploration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GameInformationTest {
    static QuestJournal.View view(Map<String,QuestStore.Saved> states){return new QuestJournal.View(states,new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,0),List.of()),ExplorationView.build(ExplorationTest.content(),ExplorationTest.empty(),List.of(),new CommunityBoundary.Status(false,List.of("조건 미등록"))));}
    static Map<String,QuestStore.Saved> empty(){return Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1),"path_marker",new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1));}
    @Test void onlyAcceptedOwnQuestsAndPublicMapInformationEnterProjection(){
        var rows=new HashMap<>(empty());rows.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));var before=Map.copyOf(rows);
        var info=GameInformation.build(QuestCatalogTest.catalog(),view(rows));assertEquals(1,info.quests().size());
        for(String secret:List.of("path_marker","hidden_place","hint_place","99.0","marker_","storyRunId","playerId"))assertFalse(info.toString().contains(secret),secret);
        assertTrue(info.places().stream().anyMatch(p->p.name().equals("?")));assertEquals(before,rows);
        assertTrue(GameInformation.build(QuestCatalogTest.catalog(),view(empty())).quests().isEmpty());
    }
    @Test void blockedAcceptedQuestIsNotExportedAndIncompleteStatesFail(){
        var rows=new HashMap<>(empty());rows.put("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));var original=view(rows);var x=original.exploration();
        var blocked=new ExplorationView(x.registered(),x.regions(),x.places(),x.facts(),x.events(),x.members(),x.story(),x.conditions(),x.nextGoal(),Set.of("survey_marker"),x.branches());
        assertTrue(GameInformation.build(QuestCatalogTest.catalog(),new QuestJournal.View(rows,original.journal(),blocked)).quests().isEmpty());
        assertThrows(IllegalArgumentException.class,()->GameInformation.build(QuestCatalogTest.catalog(),view(Map.of())));
    }
    @Test void requestsCannotChooseAnotherPlayerOrRunAndPagesAreBounded(){
        for(String bad:List.of("/hearthinfo quests player","/hearthinfo story "+UUID.randomUUID(),"/hearthinfo map 0","/hearthinfo map -1","/hearthinfo quests 10000","/hearthinformation"))assertTrue(GameInformation.Query.parse(bad).isEmpty());
        var info=GameInformation.build(QuestCatalogTest.catalog(),view(empty()));assertEquals(1,info.lines(new GameInformation.Query("quests",1)).size());assertTrue(info.lines(new GameInformation.Query("quests",1)).getFirst().contains("정보가 없습니다"));
        assertEquals(List.of("해당 페이지가 없습니다."),info.lines(new GameInformation.Query("map",2)));
        assertTrue(GameInformation.Query.parse("/hearthinfo").isPresent());assertThrows(UnsupportedOperationException.class,()->info.places().clear());
    }
    @Test void pendingReadAndDatabaseReadErrorAreNotNormalEmptyInformation(){
        var f=new PersonalQuestTest.Fixture(true);var js=new QuestBatchTest.JournalStore();var journal=ExplorationTest.journal(f,js,new ExplorationTest.Store());var ctx=f.ctx(f.a,f.p);
        var first=journal.load(ctx,new Object(),()->true);f.pump();var old=first.toCompletableFuture().join();js.delay=true;
        var pending=journal.load(ctx,new Object(),()->true);f.pump();assertFalse(pending.toCompletableFuture().isDone());
        assertThrows(IllegalStateException.class,()->journal.projectInformation(ctx,old,()->true));js.pending.completeExceptionally(new StorageFailure("read","08006"));f.pump();assertTrue(pending.toCompletableFuture().isCompletedExceptionally());
    }
    @Test void journalGatewayRejectsStaleCallerAndHoldInsteadOfReturningOldData(){
        var f=new PersonalQuestTest.Fixture(true);var js=new QuestBatchTest.JournalStore();var journal=ExplorationTest.journal(f,js,new ExplorationTest.Store());var ctx=f.ctx(f.a,f.p);Object token=new Object();
        var loading=journal.load(ctx,token,()->true);f.pump();var loaded=loading.toCompletableFuture().join();
        assertThrows(IllegalStateException.class,()->journal.projectInformation(ctx,loaded,()->false));
        assertNotNull(journal.projectInformation(ctx,loaded,()->true));js.fail=true;var failure=journal.hud(ctx,token,()->true,false);f.pump();assertTrue(failure.toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalStateException.class,()->journal.projectInformation(ctx,loaded,()->true));
    }
}
