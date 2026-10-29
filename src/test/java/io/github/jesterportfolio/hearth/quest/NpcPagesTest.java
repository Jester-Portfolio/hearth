package io.github.jesterportfolio.hearth.quest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NpcPagesTest {
    static QuestCatalog catalog(){var npc=QuestCatalogTest.npc("guide",3);npc.put("dialogue-pages",List.of("첫 공개 대화","다음 공개 대화"));return QuestCatalog.parse(List.of(npc),List.of(QuestCatalogTest.quest("test","guide")));}
    @Test void pagesRotateNonceAndPreserveQuestOptionsUntilClose(){
        var d=new QuestDialogue(catalog());UUID p=UUID.randomUUID(),r=UUID.randomUUID(),w=UUID.randomUUID(),e=UUID.randomUUID();Object s=new Object();var options=d.options(p,s,r,w,e,"guide");var first=d.beginPages(p,s,r,w,e,"guide").orElseThrow();
        var next=d.turn(p,s,r,w,e,"guide",first.nonce(),"next").orElseThrow();assertEquals(1,next.index());assertNotEquals(first.nonce(),next.nonce());assertTrue(d.current(options.getFirst()));
        assertThrows(IllegalArgumentException.class,()->d.turn(p,s,r,w,e,"guide",first.nonce(),"next"));
        var back=d.turn(p,s,r,w,e,"guide",next.nonce(),"back").orElseThrow();assertEquals(0,back.index());
        assertTrue(d.turn(p,s,r,w,e,"guide",back.nonce(),"close").isEmpty());assertFalse(d.current(options.getFirst()));assertFalse(d.current(back));
    }
    @Test void mismatchedSessionRunWorldEntityNpcAndUnloadRejectOldPages(){
        var d=new QuestDialogue(catalog());UUID p=UUID.randomUUID(),r=UUID.randomUUID(),w=UUID.randomUUID(),e=UUID.randomUUID();Object s=new Object();var page=d.beginPages(p,s,r,w,e,"guide").orElseThrow();
        assertThrows(IllegalArgumentException.class,()->d.turn(p,new Object(),r,w,e,"guide",page.nonce(),"next"));
        assertThrows(IllegalArgumentException.class,()->d.turn(p,s,UUID.randomUUID(),w,e,"guide",page.nonce(),"next"));
        assertThrows(IllegalArgumentException.class,()->d.turn(p,s,r,UUID.randomUUID(),e,"guide",page.nonce(),"next"));
        assertThrows(IllegalArgumentException.class,()->d.turn(p,s,r,w,UUID.randomUUID(),"guide",page.nonce(),"next"));
        assertThrows(IllegalArgumentException.class,()->d.turn(p,s,r,w,e,"wrong",page.nonce(),"next"));d.clearWorld(w);assertFalse(d.current(page));
    }
    @Test void malformedContentFailsAndLegacyHasNoPages(){
        assertTrue(QuestCatalogTest.catalog().pages("survey_guide").isEmpty());
        for(Object bad:List.of(List.of(),List.of(""),"invalid",List.of("x".repeat(601)))){var npc=QuestCatalogTest.npc("guide",3);npc.put("dialogue-pages",bad);assertThrows(IllegalArgumentException.class,()->QuestCatalog.parse(List.of(npc),List.of(QuestCatalogTest.quest("test","guide"))));}
    }
}
