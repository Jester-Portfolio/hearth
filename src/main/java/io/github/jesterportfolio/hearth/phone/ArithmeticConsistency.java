package io.github.jesterportfolio.hearth.phone;

import java.math.*;
import java.util.*;

/** 일반 답변의 독립된 숫자/일차 수식 줄만 검사합니다. 문제 해석·단위/모든 계산의 정답 보증은 아닙니다. */
final class ArithmeticConsistency {
    private ArithmeticConsistency(){}
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal EPS=new BigDecimal("0.000000001");
    private static final class Unsupported extends RuntimeException{}
    private static final class Invalid extends RuntimeException{}
    private record Linear(BigDecimal a,BigDecimal b,String variable){
        static Linear number(BigDecimal b){return new Linear(BigDecimal.ZERO,b,"");}
        Linear add(Linear other,boolean subtract){
            if(!variable.isEmpty()&&!other.variable.isEmpty()&&!variable.equals(other.variable))throw new Unsupported();
            return new Linear(subtract?a.subtract(other.a,MC):a.add(other.a,MC),subtract?b.subtract(other.b,MC):b.add(other.b,MC),variable.isEmpty()?other.variable:variable);
        }
        Linear multiply(Linear other){if(a.signum()!=0&&other.a.signum()!=0)throw new Unsupported();return new Linear(a.multiply(other.b,MC).add(other.a.multiply(b,MC),MC),b.multiply(other.b,MC),variable.isEmpty()?other.variable:variable);}
        Linear divide(Linear other){if(other.a.signum()!=0)throw new Unsupported();if(other.b.signum()==0)throw new Invalid();return new Linear(a.divide(other.b,MC),b.divide(other.b,MC),variable);}
    }
    static void check(String text){
        boolean code=false;int lines=0;
        for(String raw:text.split("\\R")){
            if(++lines>50)return;
            String line=raw.strip();if(line.startsWith("```")){code=!code;continue;}
            if(code||line.length()>512||!line.contains("=")||line.contains("≈")||line.contains("`"))continue;
            line=line.replace('×','*').replace('÷','/').replace('−','-').replace("⇒","→");
            if(!line.matches("[A-Za-z0-9_+*/().=→ \\tΩ옴뉴턴줄-]+"))continue;
            try{checkLine(line);}catch(Unsupported ignored){}catch(Invalid invalid){throw new AssistantFailure(AssistantFailure.Reason.INVALID_CALCULATION);}
        }
    }
    private static void checkLine(String line){
        var bindings=new HashMap<String,BigDecimal>();String[] steps=line.split("→",-1);if(steps.length>8)throw new Unsupported();
        for(String step:steps){
            String[] sides=step.split("=",-1);if(sides.length<2||sides.length>8)throw new Unsupported();
            for(int i=0;i<sides.length-1;i++){
                Linear left=new Parser(unitless(sides[i])).parse(),right=new Parser(unitless(sides[i+1])).parse();
                // 단일 변수 할당/프로그램 문장은 계산 과정으로 취급하지 않습니다.
                if(line.chars().filter(c->c=='=').count()<2&&(!left.variable.isEmpty()||!right.variable.isEmpty()))continue;
                Linear difference=left.add(right,true);
                if(difference.a.signum()==0){if(!close(left.b,right.b))throw new Invalid();}
                else{
                    BigDecimal value=difference.b.negate().divide(difference.a,MC),prior=bindings.putIfAbsent(difference.variable,value);
                    if(prior!=null&&!close(prior,value))throw new Invalid();
                }
            }
        }
    }
    private static String unitless(String value){return value.strip().replaceFirst("(?<=\\d)\\s*(?:N|J|Ω|옴|뉴턴|줄)$","").strip();}
    private static boolean close(BigDecimal a,BigDecimal b){return a.subtract(b,MC).abs().compareTo(a.abs().max(b.abs()).max(BigDecimal.ONE).multiply(EPS,MC))<=0;}
    private static final class Parser {
        private final String text;private int at,tokens,depth;
        Parser(String text){this.text=text;}
        Linear parse(){Linear value=sum();space();if(at!=text.length())throw new Unsupported();return value;}
        private Linear sum(){Linear value=product();while(true){space();if(take('+'))value=value.add(product(),false);else if(take('-'))value=value.add(product(),true);else return value;}}
        private Linear product(){Linear value=atom();while(true){space();if(take('*'))value=value.multiply(atom());else if(take('/'))value=value.divide(atom());else return value;}}
        private Linear atom(){
            space();if(++tokens>96||++depth>12)throw new Unsupported();
            try{
                if(take('+'))return atom();if(take('-'))return Linear.number(BigDecimal.ZERO).add(atom(),true);
                if(take('(')){Linear value=sum();space();if(!take(')'))throw new Unsupported();return value;}
                int begin=at;if(at<text.length()&&(Character.isLetter(text.charAt(at))||text.charAt(at)=='_')){
                    while(at<text.length()&&(Character.isLetterOrDigit(text.charAt(at))||text.charAt(at)=='_'))at++;
                    if(at-begin>16)throw new Unsupported();return new Linear(BigDecimal.ONE,BigDecimal.ZERO,text.substring(begin,at));
                }
                while(at<text.length()&&(Character.isDigit(text.charAt(at))||text.charAt(at)=='.'))at++;
                if(at==begin||at-begin>32)throw new Unsupported();try{return Linear.number(new BigDecimal(text.substring(begin,at),MC));}catch(NumberFormatException invalid){throw new Unsupported();}
            }finally{depth--;}
        }
        private void space(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}
        private boolean take(char value){if(at<text.length()&&text.charAt(at)==value){at++;return true;}return false;}
    }
}
