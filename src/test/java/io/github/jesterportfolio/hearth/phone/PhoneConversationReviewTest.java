package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneConversationReviewTest {
    private static PhoneChat chat(){return new PhoneChat(LocalAssistant.mock(),Runnable::run);}
    private static void ask(PhoneChat chat,String text){chat.submit(text).toCompletableFuture().join();}
    @Test void removingAnIncorrectAnswerAlsoRemovesLaterDependentsAndEvictsCachedReplies(){
        var chat=chat();ask(chat,"첫 질문");ask(chat,"잘못된 답을 받은 질문");ask(chat,"그 값의 두 배는?");var selected=chat.review().get(1);chat.draft("작성 중인 새 질문");
        assertTrue(chat.cacheStats().entries()>0);chat.rewind(selected,false);assertEquals(List.of("첫 질문"),chat.history().stream().map(PhoneChat.Turn::question).toList());assertEquals("작성 중인 새 질문",chat.draft());assertEquals(0,chat.cacheStats().entries());assertNull(chat.question());assertFalse(chat.canRetry());
    }
    @Test void reusingTheSelectedQuestionIsExplicitAndDoesNotSubmitIt(){
        var chat=chat();ask(chat,"다시 쓸 질문");ask(chat,"후속");chat.draft("현재 초안");chat.rewind(chat.review().getFirst(),true);assertTrue(chat.history().isEmpty());assertEquals("다시 쓸 질문",chat.draft());assertFalse(chat.busy());assertFalse(chat.canRetry());
    }
    @Test void aStaleChoiceCannotDeleteNewDialogueOrAnEqualTurnFromAnotherChat(){
        var chat=chat();ask(chat,"같은 질문");var old=chat.review().getFirst();ask(chat,"새 질문");var before=chat.history();assertThrows(IllegalStateException.class,()->chat.rewind(old,false));assertEquals(before,chat.history());
        var other=chat();ask(other,"같은 질문");assertThrows(IllegalStateException.class,()->other.rewind(chat.review().getFirst(),false));assertEquals(1,other.history().size());
    }
    @Test void rewindingCannotInterruptPendingWorkAndClosedOrClearedChoicesAreInvalid(){
        var pending=new CompletableFuture<String>();var replies=new ArrayDeque<CompletableFuture<String>>();replies.add(CompletableFuture.completedFuture("첫 답"));replies.add(pending);var chat=new PhoneChat(q->replies.removeFirst(),Runnable::run);ask(chat,"첫 질문");var choice=chat.review().getFirst();chat.submit("대기");
        assertThrows(IllegalStateException.class,()->chat.rewind(choice,false));assertFalse(pending.isDone());chat.cancel();chat.rewind(choice,false);assertTrue(chat.history().isEmpty());
        var second=chat();ask(second,"초기화");var saved=second.review().getFirst();second.clear();assertThrows(IllegalStateException.class,()->second.rewind(saved,false));second.close();assertThrows(IllegalStateException.class,()->second.rewind(saved,false));
    }
    @Test void futureGeneralAndSciencePromptsDoNotContainRemovedTurns(){
        var sent=new ArrayList<AssistantRequest>();var service=recording(sent);var chat=new PhoneChat(service,Runnable::run);chat.submitScience("오답이었던 질문",ScienceReading.LEVER,()->true).toCompletableFuture().join();chat.submitScience("잘못된 후속",ScienceReading.LEVER,()->true).toCompletableFuture().join();chat.rewind(chat.review().getFirst(),false);
        chat.submitScience("새 질문",ScienceReading.LEVER,()->true).toCompletableFuture().join();assertTrue(sent.getLast().history().isEmpty());assertFalse(sent.getLast().prompt().contains("오답이었던"));assertFalse(sent.getLast().prompt().contains("잘못된 후속"));
    }
    @Test void previewExactlyMatchesTheBoundedSameNoteHistorySentToTheBackend(){
        var sent=new ArrayList<AssistantRequest>();var chat=new PhoneChat(recording(sent),Runnable::run);chat.submitScience("지렛대",ScienceReading.LEVER,()->true).toCompletableFuture().join();chat.submitScience("에너지",ScienceReading.ENERGY,()->true).toCompletableFuture().join();
        var shown=chat.previewScience("  지렛대 후속  ",ScienceReading.LEVER);assertEquals(1,shown.history().size());assertEquals("지렛대",shown.history().getFirst().question());assertEquals("지렛대 후속",shown.question());assertEquals(2,sent.size());
        chat.submitSciencePreview(shown,ScienceReading.LEVER,()->true).toCompletableFuture().join();assertEquals(shown,sent.getLast());
    }
    @Test void changedHistorySelectionOrToggleRequiresReviewBeforeSending(){
        var sent=new ArrayList<AssistantRequest>();var chat=new PhoneChat(recording(sent),Runnable::run);chat.submitScience("이전",ScienceReading.LEVER,()->true).toCompletableFuture().join();var shown=chat.previewScience("질문",ScienceReading.LEVER);chat.draft("질문");
        chat.includeHistory(false);assertTrue(chat.submitSciencePreview(shown,ScienceReading.LEVER,()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(1,sent.size());assertEquals("질문",chat.draft());assertTrue(chat.error().contains("다시 확인"));
        var refreshed=chat.previewScience("질문",ScienceReading.LEVER);assertTrue(refreshed.history().isEmpty());assertTrue(chat.submitSciencePreview(refreshed,ScienceReading.ENERGY,()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(1,sent.size());
        chat.submitSciencePreview(refreshed,ScienceReading.LEVER,()->true).toCompletableFuture().join();assertEquals(2,sent.size());
    }
    @Test void previewRetainsExistingLengthBoundsAndCannotSendGameMaterials(){
        var chat=new PhoneChat(q->CompletableFuture.completedFuture("자료 응답 "+"가".repeat(500)+" [자료 1]"),Runnable::run);chat.submitScience("질".repeat(200),ScienceReading.LEVER,()->true).toCompletableFuture().join();var shown=chat.previewScience("새 질문",ScienceReading.LEVER);
        assertEquals(128,shown.history().getFirst().question().length());assertEquals(400,shown.history().getFirst().answer().length());assertEquals(1,shown.sources());assertEquals(ScienceReading.LEVER.context(),shown.context());
        var wrong=new AssistantRequest("새 질문",List.of(),"게임 개인 자료",1);assertTrue(chat.submitSciencePreview(wrong,ScienceReading.LEVER,()->true).toCompletableFuture().isCompletedExceptionally());assertEquals(1,chat.history().size());
        assertTrue(chat.submit("질문","",null,()->true).toCompletableFuture().isCompletedExceptionally());
    }
    private static AssistantService recording(List<AssistantRequest> sent){return new AssistantService(){public CompletionStage<String> ask(String text){throw new AssertionError();}public CompletionStage<String> ask(AssistantRequest r){sent.add(r);return CompletableFuture.completedFuture("원리 설명 [자료 1]");}};}
}
