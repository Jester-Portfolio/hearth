package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestInfoTest {
    static QuestStore.Saved missing(){return new QuestStore.Saved(QuestStore.Phase.NOT_ACCEPTED,-1);}
    static QuestCatalog catalog(){
        var first=QuestCatalogTest.npc("survey_guide",3);first.put("name","조사 안내원");
        var second=QuestCatalogTest.npc("path_guide",10);second.put("name","경로 안내원");
        var q1=QuestCatalogTest.quest("survey_marker","survey_guide");var q2=QuestCatalogTest.quest("path_marker","path_guide");
        var text=new HashMap<String,String>();for(String k:List.of("offer","accept","active","report-ready","report","completed","failure"))text.put(k,"시험 안내 "+k);q1.put("text",text);
        return QuestCatalog.parse(List.of(first,second),List.of(q1,q2));
    }
    @Test void detailUsesContentTitleNpcKoreanStatusAndOnlyCurrentStage(){
        var c=catalog();
        for(var test:Map.of(QuestStore.Phase.ACTIVE,"active",QuestStore.Phase.REPORT_READY,"report-ready",QuestStore.Phase.COMPLETED,"completed").entrySet()) {
            var state=Map.of("survey_marker",new QuestStore.Saved(test.getKey(),2),"path_marker",missing());
            var detail=QuestListView.detail(c,state,"survey_marker").orElseThrow();
            assertEquals(c.quest("survey_marker").title(),detail.quest().title());assertEquals("조사 안내원",detail.npc());assertEquals("시험 안내 "+test.getValue(),detail.guidance());
            assertEquals(QuestListView.entries(c,state).getFirst().status(),detail.quest().status());
            assertEquals(List.of("퀘스트: "+detail.quest().title(),"NPC: 조사 안내원","상태: "+detail.quest().status(),"안내: "+detail.guidance()),QuestListView.detailLines(Optional.of(detail)));
        }
    }
    @Test void unknownAndUnacceptedIdsShareExactlyTheSameNonRevealingResponse(){
        var c=catalog();var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"path_marker",missing());
        var hidden=QuestListView.detailLines(QuestListView.detail(c,rows,"path_marker"));
        assertEquals(List.of(QuestListView.UNAVAILABLE),hidden);assertEquals(hidden,QuestListView.detailLines(QuestListView.detail(c,rows,"missing")));
        assertFalse(hidden.toString().contains(c.quest("path_marker").title()));assertFalse(hidden.toString().contains("경로 안내원"));assertFalse(hidden.toString().contains("path_marker"));
        assertThrows(IllegalArgumentException.class,()->QuestListView.detail(c,Map.of(),"missing"));
    }
    @Test void acceptedListTargetsParseAndCannotSelectAnotherPlayerOrRun(){
        var c=catalog();var state=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.COMPLETED,2),"path_marker",missing());
        var rows=QuestListView.entries(c,state);assertEquals(1,rows.size());
        assertEquals(Optional.of("survey_marker"),QuestListView.infoId(QuestListView.infoCommand(rows.getFirst())));
        for(String input:List.of("/hearthquest info","/hearthquest info survey_marker other","/hearthquest info survey_marker "+UUID.randomUUID(),"/hearthquest info ../bad","/hearthquest info  survey_marker"))assertTrue(QuestListView.infoId(input).isEmpty());
    }
    @Test void detailReadKeepsPlayerRunIsolationAndAllRevisions(){
        var f=new PersonalQuestTest.Fixture(true);var c=f.ctx(f.a,f.p);var a2=f.ctx(f.a,f.p2);var b1=f.ctx(f.b,f.p);
        f.store.rows.put(f.store.key(c,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.COMPLETED,2));
        f.store.rows.put(f.store.key(a2,"path_marker"),new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1));
        f.store.rows.put(f.store.key(b1,"survey_marker"),new QuestStore.Saved(QuestStore.Phase.ACTIVE,0));var before=Map.copyOf(f.store.rows);
        var one=f.quests.list(c,new Object(),()->true).thenApply(rows->QuestListView.detail(catalog(),rows,"survey_marker"));
        var two=f.quests.list(a2,new Object(),()->true).thenApply(rows->QuestListView.detail(catalog(),rows,"survey_marker"));
        var three=f.quests.list(b1,new Object(),()->true).thenApply(rows->QuestListView.detail(catalog(),rows,"survey_marker"));f.pump();
        assertEquals(QuestStore.Phase.COMPLETED,one.toCompletableFuture().join().orElseThrow().quest().phase());assertTrue(two.toCompletableFuture().join().isEmpty());
        assertEquals(QuestStore.Phase.ACTIVE,three.toCompletableFuture().join().orElseThrow().quest().phase());assertEquals(before,f.store.rows);assertTrue(f.store.pending.isEmpty());
    }
    @Test void queryErrorAndSessionReplacementNeverBecomeUnavailableDetail(){
        var f=new PersonalQuestTest.Fixture(true);var c=f.ctx(f.a,f.p);f.store.deferReads=true;
        var failed=f.quests.list(c,new Object(),()->true).thenApply(rows->QuestListView.detail(catalog(),rows,"survey_marker"));f.pump();
        f.store.readResults.get(0).completeExceptionally(new StorageFailure("read_quest","08006"));f.pump();assertTrue(failed.toCompletableFuture().isCompletedExceptionally());
        Object token=new Object();var active=new AtomicReference<>(token);
        var stale=f.quests.list(c,token,()->active.get()==token).thenApply(rows->QuestListView.detail(catalog(),rows,"survey_marker"));f.pump();
        active.set(new Object());f.store.readResults.get(1).complete(f.store.readSnapshots.get(1));f.pump();assertTrue(stale.toCompletableFuture().isCompletedExceptionally());
    }
    @Test void writingHoldAndLateRevisionBlockDetailProjection(){
        var f=new PersonalQuestTest.Fixture(true);var c=f.ctx(f.a,f.p);var q=f.open(c,"survey_marker",new Object());
        var read=f.quests.list(c,new Object(),()->true);f.pump();var rows=read.toCompletableFuture().join();
        f.quests.advance(q,QuestStore.Phase.ACTIVE);
        assertTrue(f.quests.list(c,new Object(),()->true).thenApply(v->QuestListView.detail(catalog(),v,"survey_marker")).toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalStateException.class,()->f.quests.requireListDisplay(c,rows));f.commit(0);assertThrows(IllegalStateException.class,()->f.quests.requireListDisplay(c,rows));
        f.quests.advance(q,QuestStore.Phase.REPORT_READY);f.store.pending.get(1).completeExceptionally(new StorageFailure("quest","23514"));f.pump();
        assertTrue(f.quests.list(c,new Object(),()->true).thenApply(v->QuestListView.detail(catalog(),v,"survey_marker")).toCompletableFuture().isCompletedExceptionally());assertEquals(QuestStore.Phase.ACTIVE,q.saved().phase());
    }
}
