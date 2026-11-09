package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoneReferencesTest {
    static PhoneGrounding.Plan plan(String key,int index){var data=new GameInformation(List.of(),List.of(),List.of(new GameInformation.Diary("PERSON","같은 제목","요약","상세",false)),List.of());var selection=new PhoneHelp.Selection("diary",key,data,new PhoneRetrieval.Source("diary",index,"같은 제목","상세"));return new PhoneGrounding.Plan("diary","질문",false,List.of(new PhoneGrounding.Material(selection,1,-1)),Set.of(key));}
    @Test void lateReadCannotOverwriteOrClearNextRequestEvenWithSameMaterials(){var refs=new PhoneReferences();Object session=new Object();var old=refs.begin(session,plan("P:one",0));var current=refs.begin(session,plan("P:one",0));assertFalse(refs.update(old,plan("P:one",1)));refs.remove(old);assertSame(current,refs.get(session));assertTrue(refs.update(current,plan("P:one",2)));assertEquals(2,current.plan().materials().getFirst().item().source().index());}
    @Test void equalSessionObjectsHaveSeparateOwnershipAndClearIsExplicit(){var refs=new PhoneReferences();Object a=new String("same"),b=new String("same");var first=refs.begin(a,plan("P:one",0));var second=refs.begin(b,plan("P:two",0));refs.remove(a);assertFalse(refs.current(first));assertTrue(refs.current(second));assertNull(refs.get(a));refs.clear();assertFalse(refs.current(second));}
    @Test void requestCannotBeRefreshedIntoDifferentMaterialOrVersion(){var refs=new PhoneReferences();var old=refs.begin(new Object(),plan("P:one",0));assertThrows(IllegalStateException.class,()->refs.update(old,plan("P:two",0)));assertEquals("P:one",old.plan().materials().getFirst().item().key());}
    @Test void cancelledRequestNeverReappearsAfterLateRead(){var refs=new PhoneReferences();Object session=new Object();var request=refs.begin(session,plan("P:one",0));refs.remove(session);assertFalse(refs.update(request,plan("P:one",0)));assertFalse(refs.containsKey(session));}
}
