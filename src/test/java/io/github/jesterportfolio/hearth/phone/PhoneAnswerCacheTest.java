package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneAnswerCacheTest {
    AssistantRequest request(String question){return new AssistantRequest(question,List.of(),"자료");}
    @Test void exactRequestIncludesHistoryContextAndSourceContract(){
        var cache=new PhoneAnswerCache();cache.select("scope");var key=request("질문");cache.put(key,"답변");assertEquals("답변",cache.find(key).orElseThrow());
        for(var other:List.of(request("다른 질문"),new AssistantRequest("질문",List.of(new PhoneChat.Turn("이전","응답")),"자료"),new AssistantRequest("질문",List.of(),"변경 자료"),new AssistantRequest("질문",List.of(),"자료",1)))assertTrue(cache.find(other).isEmpty());
    }
    @Test void scopeChangeAndConversationClearDiscardOriginalEntries(){
        var cache=new PhoneAnswerCache();cache.select("revision1");cache.put(request("질문"),"원래 답변");cache.select("revision2");assertTrue(cache.find(request("질문")).isEmpty());cache.select("revision1");assertTrue(cache.find(request("질문")).isEmpty());cache.clear();assertEquals(new PhoneAnswerCache.Stats(0,0,0,0),cache.stats());
    }
    @Test void expiresWithoutRefreshingAgeAndRejectsClockReversal(){
        var clock=new AtomicLong();var cache=new PhoneAnswerCache(clock::get);cache.put(request("질문"),"답변");clock.set(59_000_000_000L);assertTrue(cache.find(request("질문")).isPresent());clock.set(60_000_000_000L);assertTrue(cache.find(request("질문")).isEmpty());assertEquals(0,cache.stats().bytes());cache.put(request("새 질문"),"답변");clock.set(0);assertTrue(cache.find(request("새 질문")).isEmpty());
    }
    @Test void lruAndByteBoundsKeepOnlyFourFiniteAnswers(){
        var cache=new PhoneAnswerCache();for(int i=0;i<4;i++)cache.put(request("질문"+i),"답변");cache.find(request("질문0"));cache.put(request("질문4"),"답변");assertTrue(cache.find(request("질문1")).isEmpty());assertTrue(cache.find(request("질문0")).isPresent());cache.put(request("과대"),"x".repeat(8193));assertTrue(cache.find(request("과대")).isEmpty());assertEquals(4,cache.stats().entries());assertTrue(cache.stats().bytes()<=128*1024);
    }
}
