package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneExcerptTest {
    @Test void lateAnswerIsReachableAndExcerptIsOneContiguousUnicodeSafeSpan(){
        String text="배경😀 안내입니다. ".repeat(100)+"\n운반품은 판자이며 오늘은 창고로 옮깁니다.\n"+"다른 안내입니다. ".repeat(100);
        String result=PhoneExcerpt.select("운반품은 어디로 옮기나요",text);assertTrue(result.contains("운반품은 판자"));assertTrue(result.startsWith("… "));assertTrue(result.endsWith(" …"));assertTrue(result.codePointCount(0,result.length())<=400);
        assertTrue(text.contains(result.substring(2,result.length()-2)));assertFalse(Character.isLowSurrogate(result.charAt(2)));assertFalse(Character.isHighSurrogate(result.charAt(result.length()-3)));
    }
    @Test void multipleMatchingTermsBeatRepeatedSingleTermAndTiesAreStable(){
        String text="목재 안내. ".repeat(80)+"\n목재 운반품 판자 창고\n"+"일반 안내. ".repeat(80);String result=PhoneExcerpt.select("목재 운반품 창고",text);assertTrue(result.contains("목재 운반품 판자 창고"));assertEquals(result,PhoneExcerpt.select("목재 운반품 창고",text));
    }
    @Test void shortOriginalIsUnchangedAndNoMatchUsesMarkedBeginning(){
        assertEquals("짧은 공개 내용",PhoneExcerpt.select("질문","짧은 공개 내용"));String result=PhoneExcerpt.select("비일치","가😀".repeat(300));assertTrue(result.endsWith(" …"));assertFalse(result.startsWith("… "));assertTrue(result.codePointCount(0,result.length())<=400);
    }
    @Test void normalizedQuestionMatchesOriginalTextWithoutReplacingIt(){
        String text="공개 안내. ".repeat(90)+"\nＩＲＯＮ 운반품은 못이다.\n";var result=PhoneExcerpt.select("iron 운반품",text);assertTrue(result.contains("ＩＲＯＮ"));assertFalse(result.contains("iron"));
    }
    @Test void retrievalAndFullPagesKeepTheLatePublicBody(){
        String detail="합성 배경. ".repeat(120)+"\n운반품은 판자다.\n";var info=new GameInformation(List.of(),List.of(),List.of(new GameInformation.Diary("PERSON","공방 기록","공개 요약",detail,false)),List.of());
        assertTrue(PhoneRetrieval.find(info,"diary","운반품").context().contains("운반품은 판자"));assertTrue(PhonePages.body(info,"diary",0).contains(detail));assertTrue(String.join("",PhonePages.detail(info,"diary",0).pages()).contains(detail));
    }
    @Test void longFocusedSourcesKeepPublicStatusAndPersonalOrSharedMeaning(){
        String body="일반 안내. ".repeat(100)+"\n운반품은 판자다.\n";var data=new GameInformation(List.of(new GameInformation.Quest("공방 의뢰","목수","보고 가능",body)),List.of(new GameInformation.Place("공방","지역",body,"미발견",false)),List.of(new GameInformation.Diary("STORY","공방 기록","요약",body,true)),List.of(body));
        for(String mode:List.of("quests","map","diary","story")){var result=PhoneRetrieval.find(data,mode,"운반품");assertEquals(1,result.sources().size());String text=result.sources().getFirst().text();assertTrue(text.contains("운반품은 판자"));assertTrue(text.codePointCount(0,text.length())<=400);assertTrue(text.contains("발췌:"));if(mode.equals("diary")||mode.equals("story"))assertTrue(text.contains("개인 완료/보상과 별개"));if(mode.equals("quests"))assertTrue(text.contains("보고 가능"));if(mode.equals("map"))assertTrue(text.contains("미발견")&&text.contains("시설에서 선택"));}
    }
}
