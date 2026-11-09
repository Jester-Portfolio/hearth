package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneChatMaterialScopeTest {
    @Test void sameExcerptFromDifferentMaterialNeverIncludesPreviousMaterialHistoryOrInternalScope(){
        var prompts=new ArrayList<String>();var chat=new PhoneChat(prompt->{prompts.add(prompt);return CompletableFuture.completedFuture("이전 자료 응답");},Runnable::run);
        chat.submit("첫 질문","같은 발췌문","diary",()->true,()->CompletableFuture.completedFuture(true),"private_material_one").toCompletableFuture().join();
        chat.submit("다른 자료 질문","같은 발췌문","diary",()->true,()->CompletableFuture.completedFuture(true),"private_material_two").toCompletableFuture().join();assertFalse(prompts.get(1).contains("첫 질문"));assertFalse(prompts.get(1).contains("이전 자료 응답"));
        chat.submit("첫 자료 후속","같은 발췌문","diary",()->true,()->CompletableFuture.completedFuture(true),"private_material_one").toCompletableFuture().join();assertTrue(prompts.get(2).contains("첫 질문"));for(String prompt:prompts){assertFalse(prompt.contains("private_material_one"));assertFalse(prompt.contains("private_material_two"));}
    }
    @Test void allScopedRequestsWaitForFreshValidationAndPreserveDraftWhenRejected(){var gate=new CompletableFuture<Boolean>();var chat=new PhoneChat(p->CompletableFuture.completedFuture("늦은 자료 응답"),Runnable::run);var result=chat.submit("유지할 질문","공개 자료","story",()->true,()->gate,"scope");assertTrue(chat.busy());assertTrue(chat.history().isEmpty());gate.complete(false);assertTrue(result.toCompletableFuture().isCompletedExceptionally());assertTrue(chat.history().isEmpty());assertEquals("유지할 질문",chat.draft());assertTrue(chat.canRetry());}
}
