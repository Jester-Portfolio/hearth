package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.function.Supplier;

/** 같은 고정식의 두 사용자 입력을 정확한 유리수로 비교합니다. 인과관계나 게임 효과를 판정하지 않습니다. */
public final class ScienceComparison {
    public record Input(String label,String baseline,String compared,boolean changed){}
    public record Measure(String exact,String approximation,String notice){
        public Measure{if((exact==null)==(notice==null)||exact==null&&approximation!=null)throw new IllegalArgumentException("COMPARISON_MEASURE");}
        public boolean available(){return exact!=null;}
        private List<String> lines(String label,String unit){
            if(!available())return List.of(label+": "+notice);var values=new ArrayList<String>();values.add(label+" · 정확값: "+exact+unit);
            if(approximation!=null)values.add(label+" · 근삿값(유효숫자 최대 20자리): "+approximation+unit);return values;
        }
    }
    private final ScienceCalculationSnapshot baseline,compared;private final List<Input> inputs;private final Measure difference,ratio;private final List<String> lines;
    private ScienceComparison(ScienceCalculationSnapshot baseline,ScienceCalculationSnapshot compared){
        this.baseline=Objects.requireNonNull(baseline);this.compared=Objects.requireNonNull(compared);if(baseline.plan().topic()!=compared.plan().topic())throw new IllegalArgumentException("COMPARISON_TOPIC_MISMATCH");
        var topic=baseline.plan().topic();var changes=new ArrayList<Input>();
        for(int i=0;i<topic.fields().size();i++){
            String before=baseline.plan().inputs().get(i),after=compared.plan().inputs().get(i);boolean changed=!ExactArithmetic.evaluate(before,null).value().equals(ExactArithmetic.evaluate(after,null).value());changes.add(new Input(topic.fields().get(i).label(),before,after,changed));
        }
        inputs=List.copyOf(changes);var before=ExactArithmetic.evaluate(baseline.plan().expression(),null).value();var after=ExactArithmetic.evaluate(compared.plan().expression(),null).value();
        difference=measure(()->after.add(before.negate()));ratio=before.numerator().signum()==0?new Measure(null,null,"기준 결과가 0이므로 비율을 정의할 수 없습니다."):measure(()->after.divide(before));
        var text=new ArrayList<String>();text.add(topic.title()+" · 기준 → 비교");text.add("직접 입력한 두 계산을 같은 공식·가정으로 비교합니다. 게임 상태·측정값이나 제작 가능 여부를 확인한 결과가 아닙니다.");text.add(topic.formula());text.add("공통 가정: "+topic.assumption());
        text.add("수치가 바뀐 입력: "+inputs.stream().filter(Input::changed).count()+"개. 여러 조건을 바꾼 경우 한 조건만의 영향으로 해석하지 마세요.");
        for(var input:inputs)text.add(input.label()+": "+input.baseline()+" → "+input.compared()+(input.changed()?" · 수치 변경":" · 같은 수치"));
        text.add("기준 식: "+baseline.plan().expression());text.add("비교 식: "+compared.plan().expression());text.add("기준 결과 · 정확값: "+baseline.exact()+" "+topic.unit());text.add("비교 결과 · 정확값: "+compared.exact()+" "+topic.unit());
        if(baseline.approximation()!=null)text.add("기준 결과 · 근삿값(유효숫자 최대 20자리): "+baseline.approximation()+" "+topic.unit());if(compared.approximation()!=null)text.add("비교 결과 · 근삿값(유효숫자 최대 20자리): "+compared.approximation()+" "+topic.unit());
        text.addAll(difference.lines("결과 변화량 (비교 − 기준)"," "+topic.unit()));text.addAll(ratio.lines("결과 비율 (비교 / 기준)","배"));text.add("비교는 읽기 전용입니다. 원래 계산·이전값·AI 선택/초안은 바꾸거나 자동 전송하지 않습니다.");lines=List.copyOf(text);
    }
    static ScienceComparison of(ScienceCalculationSnapshot baseline,ScienceCalculationSnapshot compared){return new ScienceComparison(baseline,compared);}
    private static Measure measure(Supplier<ExactArithmetic.Value> operation){try{var value=operation.get();return new Measure(value.exact(),value.approximation(),null);}catch(ExactArithmetic.Rejected limited){return new Measure(null,null,"계산 한도를 넘어 표시할 수 없습니다. 원래 두 결과는 보존합니다.");}}
    public ScienceCalculationSnapshot baseline(){return baseline;}public ScienceCalculationSnapshot compared(){return compared;}public List<Input> inputs(){return inputs;}public Measure difference(){return difference;}public Measure ratio(){return ratio;}public List<String> lines(){return lines;}
}
