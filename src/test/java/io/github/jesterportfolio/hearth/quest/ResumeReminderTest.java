package io.github.jesterportfolio.hearth.quest;

import io.github.jesterportfolio.hearth.story.SharedStory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ResumeReminderTest {
    final CourseResume.Board board=CourseResumeTest.board(CourseResumeTest.view());
    @Test void currentSessionIsAnnouncedOnceWhileOtherPlayerIsIndependent(){var r=new ResumeReminder();UUID p=UUID.randomUUID();Object token=new Object();assertTrue(r.claim(p,token,true,board).isPresent());assertTrue(r.claim(p,token,true,board).isEmpty());assertTrue(r.claim(UUID.randomUUID(),token,true,board).isPresent());assertTrue(r.current(p,token));}
    @Test void pendingHoldOrRejectedLateCallerDoesNotConsumeFutureHealthyHint(){var r=new ResumeReminder();UUID p=UUID.randomUUID();Object token=new Object();var hold=CourseResume.build(CourseResumeTest.plan(),CourseResumeTest.catalog(),CourseResumeTest.view(),SharedStoryTest.empty(),SharedStory.Phase.HOLD);assertTrue(r.claim(p,token,true,hold).isEmpty());assertTrue(r.claim(p,token,false,board).isEmpty());assertFalse(r.current(p,token));assertTrue(r.claim(p,token,true,board).isPresent());}
    @Test void reconnectUsesTokenIdentityAndOldCallbackOrQuitCannotClearNewHint(){var r=new ResumeReminder();UUID p=UUID.randomUUID();Object old=new String("same"),fresh=new String("same");r.claim(p,old,true,board);assertTrue(r.claim(p,fresh,true,board).isPresent());assertTrue(r.claim(p,old,false,board).isEmpty());r.forget(p,old);assertTrue(r.current(p,fresh));assertFalse(r.current(p,old));assertTrue(r.claim(p,fresh,true,board).isEmpty());}
    @Test void forgettingExactSessionOrClosingClearsOnlyOwnedReminder(){var r=new ResumeReminder();UUID a=UUID.randomUUID(),b=UUID.randomUUID();Object one=new Object(),two=new Object();r.claim(a,one,true,board);r.claim(b,two,true,board);r.forget(a,one);assertFalse(r.current(a,one));assertTrue(r.current(b,two));r.clear();assertFalse(r.current(b,two));}
}
