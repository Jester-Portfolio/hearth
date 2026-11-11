package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 플레이어가 명시한 SI 수치와 고정된 기본 공식만 사용합니다. 게임 상태/AI 답변을 읽지 않습니다. */
public final class ScienceCalculation {
    private ScienceCalculation(){}
    public record Field(String label,boolean allowZero){}
    public enum Topic {
        LEVER("지렛대 균형","N",List.of(new Field("하중 (N)",true),new Field("하중 팔 길이 (m)",false),new Field("작용 팔 길이 (m)",false)),"정적 평형인 이상적인 지렛대. 지렛대 자체 무게·마찰 제외. 힘과 팔은 수직.","필요한 힘 = 하중 × 하중 팔 / 작용 팔"),
        ENERGY("중력 위치 에너지 증가","J",List.of(new Field("질량 (kg)",true),new Field("높이 증가 (m)",true),new Field("중력 가속도 (m/s²)",false)),"중력 가속도가 일정한 범위의 높이 증가. 중력 가속도도 직접 입력하세요.","에너지 증가 = 질량 × 중력 가속도 × 높이 증가"),
        SERIES("직렬 합성 저항","Ω",List.of(new Field("첫 번째 저항 (Ω)",true),new Field("두 번째 저항 (Ω)",true)),"이상적인 두 저항의 직렬 연결. 온도 변화 등은 제외.","합성 저항 = 첫 번째 저항 + 두 번째 저항");
        private final String title,unit,assumption,formula;private final List<Field> fields;
        Topic(String title,String unit,List<Field> fields,String assumption,String formula){this.title=title;this.unit=unit;this.fields=List.copyOf(fields);this.assumption=assumption;this.formula=formula;}
        public String title(){return title;}public String unit(){return unit;}public List<Field> fields(){return fields;}public String assumption(){return assumption;}public String formula(){return formula;}
    }
    public static final class Plan {
        private final Topic topic;private final String expression;
        private Plan(Topic topic,String expression){this.topic=topic;this.expression=expression;}
        public Topic topic(){return topic;}public String expression(){return expression;}
    }
    public static Plan prepare(Topic topic,List<String> inputs){
        Objects.requireNonNull(topic);if(inputs==null||inputs.size()!=topic.fields().size())throw new IllegalArgumentException("모든 수치를 입력해 주세요.");
        var values=new ArrayList<String>();
        for(int i=0;i<inputs.size();i++){
            String value=inputs.get(i);value=value==null?"":value.strip();
            if(value.length()>32||!value.matches("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,2})?"))throw new IllegalArgumentException(topic.fields().get(i).label()+": 단위 없이 32자 이내의 양수 또는 허용된 0을 입력해 주세요.");
            var number=ExactArithmetic.evaluate(value,null).value();if(number.numerator().signum()==0&&!topic.fields().get(i).allowZero())throw new IllegalArgumentException(topic.fields().get(i).label()+": 0보다 큰 값을 입력해 주세요.");values.add("("+value+")");
        }
        String expression=switch(topic){case LEVER->values.get(0)+"*"+values.get(1)+"/"+values.get(2);case ENERGY->values.get(0)+"*"+values.get(2)+"*"+values.get(1);case SERIES->values.get(0)+"+"+values.get(1);};
        return new Plan(topic,expression);
    }
}
