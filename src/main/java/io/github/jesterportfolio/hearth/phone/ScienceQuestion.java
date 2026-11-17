package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** UI 선택과 미리보기가 같은 읽기/계산 자료를 가리키도록 합니다. */
record ScienceQuestion(ScienceReading reading,ScienceCalculationSnapshot calculation){
    ScienceQuestion{Objects.requireNonNull(reading);if(calculation!=null&&calculation.reading()!=reading)throw new IllegalArgumentException("SCIENCE_TOPIC_MISMATCH");}
    static ScienceQuestion reading(ScienceReading reading){return new ScienceQuestion(reading,null);}
    static ScienceQuestion calculation(ScienceCalculationSnapshot snapshot){return new ScienceQuestion(snapshot.reading(),snapshot);}
    String mode(){return calculation==null?"research":"calculation";}String context(){return calculation==null?reading.context():calculation.context();}
    Object material(){return calculation==null?reading:calculation;}
    String label(){return calculation==null?reading.title():"검산 계산 · "+calculation.label();}
    AssistantRequest request(String question,List<PhoneChat.Turn> history){return calculation==null?AssistantRequest.science(question,history,reading):AssistantRequest.calculation(question,history,calculation);}
}
