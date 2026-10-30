package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class QuestBookViewTest {
    static QuestJournal.View view(Map<String,QuestStore.Saved> rows){return new QuestJournal.View(rows,new QuestJournalStore.Journal(Set.of(),new QuestJournalStore.Preferences(true,-1),List.of()));}
    static String text(QuestBookView book){return String.join("",book.pages()).replace("\n","");}
    @Test void acceptedBookShowsTitleNpcStateCurrentStepWithoutChangingRows(){
        var catalog=QuestBatchTest.content(false);
        for(var phase:List.of(QuestStore.Phase.ACTIVE,QuestStore.Phase.REPORT_READY,QuestStore.Phase.COMPLETED)){
            var rows=Map.of("survey_marker",new QuestStore.Saved(phase,3,1));var before=Map.copyOf(rows);
            var book=QuestBookView.accepted(catalog,rows,Set.of(),"survey_marker").orElseThrow();
            assertEquals(catalog.quest("survey_marker").title(),book.title());assertTrue(text(book).contains(catalog.quest("survey_marker").name()));assertTrue(text(book).contains(QuestListView.status(phase)));
            if(phase==QuestStore.Phase.ACTIVE){assertTrue(text(book).contains("다음 목표"));assertFalse(text(book).contains("마지막 목표"));}
            assertEquals(before,rows);
        }
    }
    @Test void unknownUnacceptedAndBlockedDetailsDoNotExposeAnything(){
        var c=QuestInfoTest.catalog();var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,0),"path_marker",QuestInfoTest.missing());
        for(String id:List.of("path_marker","unknown"))assertTrue(QuestBookView.accepted(c,rows,Set.of(),id).isEmpty());
        assertTrue(QuestBookView.accepted(c,rows,Set.of("survey_marker"),"survey_marker").isEmpty());
        assertThrows(IllegalArgumentException.class,()->QuestBookView.accepted(c,Map.of(),Set.of(),"survey_marker"));
    }
    @Test void npcItemsShowStateAndUseSeparateValidatedActions(){
        var c=QuestDialogueTest.shared();var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.REPORT_READY,1),"path_marker",QuestInfoTest.missing());
        var menu=QuestScreens.npc(c,"survey_guide",rows,q->true,0);
        assertEquals(2,menu.stream().filter(b->b.action().equals("npc-select")).count());assertEquals(2,menu.stream().filter(b->b.action().equals("npc-action")).count());
        assertTrue(menu.stream().anyMatch(b->b.label().equals("[보고 가능] "+c.quest("survey_marker").title())));
        assertEquals(1,menu.stream().filter(b->b.quest()!=null&&b.quest().equals("survey_marker")&&b.action().equals("npc-action")).findFirst().orElseThrow().revision());
        assertEquals(menu.size(),menu.stream().map(QuestScreens.Button::slot).distinct().count());
        assertFalse(QuestScreens.npc(c,"survey_guide",rows,q->!q.id().equals("path_marker"),0).stream().anyMatch(b->"path_marker".equals(b.quest())));
    }
    @Test void automaticUnacceptedQuestHasDetailsButNoNpcAcceptance(){
        var n=QuestCatalogTest.npc("guide",3);var q=QuestCatalogTest.quest("survey_marker","guide");q.put("start-mode","automatic");q.put("start-area",Map.of("min-x",1,"max-x",2,"min-y",64,"max-y",66,"min-z",1,"max-z",2));
        var c=QuestCatalog.parse(List.of(n),List.of(q));var rows=Map.of("survey_marker",QuestInfoTest.missing());
        assertTrue(QuestScreens.npc(c,"guide",rows,x->true,0).stream().anyMatch(b->b.action().equals("npc-select")));
        assertFalse(QuestScreens.npc(c,"guide",rows,x->true,0).stream().anyMatch(b->b.action().equals("npc-action")));
    }
    @Test void acceptedManagementPutsDescriptionInBookInsteadOfItemNames(){
        var c=QuestBatchTest.content(false);var rows=Map.of("survey_marker",new QuestStore.Saved(QuestStore.Phase.ACTIVE,1,1));
        var buttons=QuestScreens.screen(c,view(rows),"detail","survey_marker",0);
        assertTrue(buttons.stream().anyMatch(b->b.action().equals("book")&&b.label().contains("진행 중")));
        assertFalse(buttons.stream().anyMatch(b->b.label().contains("다음 목표")));assertTrue(text(QuestBookView.accepted(c,rows,Set.of(),"survey_marker").orElseThrow()).contains("다음 목표"));
    }
    @Test void longKoreanAndSupplementaryCharactersArePreservedAcrossPages(){
        String source="긴 설명입니다😀. ".repeat(90);var pages=QuestBookView.paginate(List.of(source));
        assertTrue(pages.size()>1);assertEquals(source,String.join("",pages).replace("\n",""));
        for(String page:pages){assertTrue(page.split("\n",-1).length<=13);for(String line:page.split("\n"))assertTrue(line.codePointCount(0,line.length())<=12);}
        assertThrows(IllegalArgumentException.class,()->QuestBookView.paginate(List.of("가".repeat(15000))));
    }
    @Test void itemSelectionCannotUseAnotherNpcSessionOrConsumedTicket(){
        var f=new QuestDialogueTest();var t=f.options().getFirst();assertSame(t,f.d.selection(f.player,f.session,t.npc(),t.quest()));
        assertThrows(IllegalArgumentException.class,()->f.d.selection(f.player,new Object(),t.npc(),t.quest()));
        assertThrows(IllegalArgumentException.class,()->f.d.selection(f.player,f.session,"missing",t.quest()));
        assertThrows(IllegalArgumentException.class,()->f.d.selection(f.player,f.session,t.npc(),"unknown"));
        f.authorize(t);assertThrows(IllegalArgumentException.class,()->f.d.selection(f.player,f.session,t.npc(),t.quest()));
        f.d.clearWorld(f.world);assertThrows(RuntimeException.class,()->f.d.selection(f.player,f.session,t.npc(),t.quest()));
    }
    @Test void npcDialogueBookUsesCurrentContentAndEmptyListsDoNotInventQuests(){
        var c=QuestInfoTest.catalog();var q=c.quest("survey_marker");
        var book=QuestBookView.dialogue(q,QuestInfoTest.missing());assertTrue(text(book).contains(q.text().get("offer")));assertTrue(text(book).contains("미수락"));
        var rows=Map.of("survey_marker",QuestInfoTest.missing(),"path_marker",QuestInfoTest.missing());
        assertTrue(QuestScreens.screen(c,view(rows),"quests",null,0).stream().anyMatch(b->b.label().equals("수락한 퀘스트가 없습니다")));
    }
}
