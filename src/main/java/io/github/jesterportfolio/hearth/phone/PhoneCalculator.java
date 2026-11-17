package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** owner 스레드 전용. 계산 기록은 접속 메모리 전용이며 구조화 계산의 명시적 선택만 설명 자료로 만듭니다. */
public final class PhoneCalculator implements AutoCloseable {
    public record Entry(String expression,String previous,String exact,String approximation,ScienceCalculation.Plan science){
        public List<String> lines(){
            var lines=new ArrayList<String>();if(science!=null){lines.add(science.topic().title()+" · "+science.topic().formula());lines.add(science.topic().assumption());}lines.add("식: "+expression);
            if(previous!=null)lines.add("사용한 이전값(정확값): "+previous);
            String unit=science==null?"":" "+science.topic().unit();lines.add("정확값: "+exact+unit);if(approximation!=null)lines.add("근삿값(유효숫자 20자리, 반올림): "+approximation+unit);
            return List.copyOf(lines);
        }
    }
    private final List<Entry> history=new ArrayList<>();private ExactArithmetic.Value previous;
    private String draft="",error;private boolean closed;
    public List<Entry> history(){return List.copyOf(history);}
    public boolean current(Entry entry){return !closed&&entry!=null&&history.stream().anyMatch(saved->saved==entry);}
    public String draft(){return draft;}public String error(){return error;}public boolean hasPrevious(){return previous!=null;}
    public void draft(String value){requireOpen();if(value==null||value.length()>128)throw new IllegalArgumentException("CALCULATOR_DRAFT_LENGTH");draft=value;}
    public boolean calculate(String expression){return calculate(expression,null);}
    public boolean calculateScience(ScienceCalculation.Plan plan){return calculate(plan.expression(),plan);}
    public ScienceCalculationSnapshot select(Entry entry){
        requireOpen();if(entry==null||entry.science()==null||!current(entry))throw new IllegalStateException("STALE_CALCULATION_SELECTION");
        return ScienceCalculationSnapshot.from(entry.science());
    }
    private boolean calculate(String expression,ScienceCalculation.Plan science){
        requireOpen();if(science==null&&expression!=null&&expression.length()<=128)draft=expression;
        try{
            var result=ExactArithmetic.evaluate(expression,previous);var value=result.value();
            history.add(new Entry(expression.strip(),result.usedPrevious()?previous.exact():null,value.exact(),value.approximation(),science));
            while(history.size()>4)history.removeFirst();previous=value;error=null;if(science==null)draft="";return true;
        }catch(ExactArithmetic.Rejected rejected){error=rejected.getMessage();return false;}
    }
    public void continuePrevious(){requireOpen();if(!hasPrevious())throw new IllegalStateException("CALCULATOR_PREVIOUS_REQUIRED");draft="이전값 * ";error=null;}
    public void clear(){requireOpen();history.clear();previous=null;draft="";error=null;}
    private void requireOpen(){if(closed)throw new IllegalStateException("CALCULATOR_CLOSED");}
    @Override public void close(){history.clear();previous=null;draft="";error=null;closed=true;}
}
