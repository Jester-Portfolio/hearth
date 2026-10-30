package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhonePagesTest {
    static GameInformation data(){return new GameInformation(java.util.stream.IntStream.range(0,13).mapToObj(i->new GameInformation.Quest("공개 퀘스트 "+i,"안내원","진행 중","현재 목표 "+i)).toList(),List.of(new GameInformation.Place("?","공개 지역","대략적 탐색 구역","미발견",false)),List.of(new GameInformation.Diary("EXPLORATION","발견 기록","요약","긴 기록😀".repeat(300),false)),List.of("공유 진행 미등록"));}
    @Test void allItemsAreReachableAndPagesDoNotOverlap(){
        var info=data();var indices=new ArrayList<Integer>();for(int p=1;p<=3;p++){var page=PhonePages.list(info,"quests",p);assertEquals(3,page.total());page.entries().forEach(e->indices.add(e.index()));}
        assertEquals(java.util.stream.IntStream.range(0,13).boxed().toList(),indices);
        assertThrows(IllegalArgumentException.class,()->PhonePages.list(info,"quests",0));assertThrows(IllegalArgumentException.class,()->PhonePages.list(info,"quests",4));
    }
    @Test void detailOnlyUsesApprovedDtoAndDoesNotInferHiddenCoordinates(){
        var info=data();var before=info.toString();var detail=PhonePages.detail(info,"map",0);
        assertEquals("?",detail.title());assertTrue(detail.pages().getFirst().contains("대략적 탐색 구역"));assertTrue(detail.pages().getFirst().contains("미해금"));
        assertEquals(before,info.toString());assertThrows(IllegalArgumentException.class,()->PhonePages.detail(info,"map",1));assertThrows(IllegalArgumentException.class,()->PhonePages.detail(info,"quests",-1));assertThrows(IllegalArgumentException.class,()->PhonePages.list(info,"unknown",1));
    }
    @Test void longDiaryIsCompleteAndUnicodeSafe(){
        var info=data();var detail=PhonePages.detail(info,"diary",0);assertTrue(detail.pages().size()>1);String text=String.join("",detail.pages());assertTrue(text.endsWith(info.diary().getFirst().detail()));
        for(String page:detail.pages()){assertTrue(page.codePointCount(0,page.length())<=480);assertFalse(Character.isHighSurrogate(page.charAt(page.length()-1)));}
    }
    @Test void emptyAndPersonalDataStayDistinct(){
        var empty=new GameInformation(List.of(),List.of(),List.of(),List.of());assertTrue(PhonePages.list(empty,"quests",1).entries().isEmpty());assertEquals(1,PhonePages.list(empty,"quests",1).total());assertFalse(PhonePages.list(data(),"quests",1).entries().isEmpty());
    }
}
