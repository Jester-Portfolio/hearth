package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 사용자가 선택한 검증된 고정식/숫자만 담습니다. 게임/인벤토리·다른 계산 기록은 읽지 않습니다. */
public final class ScienceCalculationSnapshot {
    private final ScienceCalculation.Plan plan;private final ScienceReading reading;private final String exact,approximation,context;
    private ScienceCalculationSnapshot(ScienceCalculation.Plan plan){
        this.plan=Objects.requireNonNull(plan);reading=Arrays.stream(ScienceReading.values()).filter(note->note.calculation()==plan.topic()).findFirst().orElseThrow();
        var value=ExactArithmetic.evaluate(plan.expression(),null).value();exact=value.exact();approximation=value.approximation();
        var lines=new ArrayList<String>();lines.add(reading.context());lines.add("[자료 2] 사용자가 선택한 서버 계산기 기록 · "+plan.topic().title());
        for(int i=0;i<plan.inputs().size();i++)lines.add(plan.topic().fields().get(i).label()+": "+plan.inputs().get(i));
        lines.add(plan.topic().formula());lines.add("입력식: "+plan.expression());lines.add("입력값 기준 정확값: "+exact+" "+plan.topic().unit());
        if(approximation!=null)lines.add("근삿값(유효숫자 최대 20자리): "+approximation+" "+plan.topic().unit());
        lines.add("가정: "+plan.topic().assumption());lines.add("입력한 수를 기준으로 계산했습니다. 게임 상태/측정값·제작 가능 여부를 확인한 기록이 아닙니다.");context=String.join("\n",lines);
        if(context.codePointCount(0,context.length())>2400)throw new IllegalArgumentException("CALCULATION_REFERENCE_LENGTH");
    }
    static ScienceCalculationSnapshot from(ScienceCalculation.Plan plan){return new ScienceCalculationSnapshot(plan);}
    public ScienceCalculation.Plan plan(){return plan;}public ScienceReading reading(){return reading;}public String exact(){return exact;}public String approximation(){return approximation;}public String context(){return context;}
    public String label(){return plan.topic().title()+" · "+plan.expression()+" → "+exact+" "+plan.topic().unit();}
    @Override public boolean equals(Object other){return other instanceof ScienceCalculationSnapshot snapshot&&context.equals(snapshot.context);}
    @Override public int hashCode(){return context.hashCode();}
}
