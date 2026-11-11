package io.github.jesterportfolio.hearth.phone;

import java.math.*;

/** 직접 입력한 유한 산술식만 계산합니다. 자연어·단위·모델 응답을 실행하지 않습니다. */
final class ExactArithmetic {
    private ExactArithmetic(){}
    enum Problem {
        SYNTAX("숫자와 + - * / ^, 괄호만 입력해 주세요. 단위와 설명은 식에서 빼 주세요."),
        ZERO("0으로 나눌 수 없습니다. 식을 확인해 주세요."),
        LIMIT("계산 한도를 넘었습니다. 숫자나 식을 더 작게 나눠 주세요."),
        EXPONENT("거듭제곱의 지수는 -12~12의 정수로 입력해 주세요. 0^0은 지원하지 않습니다."),
        PREVIOUS("아직 이전 계산값이 없습니다. 먼저 숫자로 된 식을 계산해 주세요.");
        final String message;Problem(String message){this.message=message;}
    }
    static final class Rejected extends IllegalArgumentException {
        final Problem problem;Rejected(Problem problem){super(problem.message);this.problem=problem;}
    }
    private static Rejected reject(Problem problem){return new Rejected(problem);}
    record Value(BigInteger numerator,BigInteger denominator) {
        Value {
            if(denominator.signum()==0)throw reject(Problem.ZERO);
            if(denominator.signum()<0){numerator=numerator.negate();denominator=denominator.negate();}
            var gcd=numerator.gcd(denominator);numerator=numerator.divide(gcd);denominator=denominator.divide(gcd);
            if(numerator.abs().bitLength()>256||denominator.bitLength()>256)throw reject(Problem.LIMIT);
        }
        static Value integer(long n){return new Value(BigInteger.valueOf(n),BigInteger.ONE);}
        Value negate(){return new Value(numerator.negate(),denominator);}
        Value add(Value other){return new Value(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),denominator.multiply(other.denominator));}
        Value multiply(Value other){return new Value(numerator.multiply(other.numerator),denominator.multiply(other.denominator));}
        Value divide(Value other){return new Value(numerator.multiply(other.denominator),denominator.multiply(other.numerator));}
        Value power(Value exponent){
            if(!exponent.denominator.equals(BigInteger.ONE)||exponent.numerator.abs().compareTo(BigInteger.valueOf(12))>0)throw reject(Problem.EXPONENT);
            int n=exponent.numerator.intValue();if(n==0&&numerator.signum()==0)throw reject(Problem.EXPONENT);
            return n>=0?new Value(numerator.pow(n),denominator.pow(n)):new Value(denominator.pow(-n),numerator.pow(-n));
        }
        String fraction(){return denominator.equals(BigInteger.ONE)?numerator.toString():numerator+"/"+denominator;}
        String exact(){
            try{String decimal=new BigDecimal(numerator).divide(new BigDecimal(denominator)).stripTrailingZeros().toPlainString();if(decimal.length()<=96)return decimal;}
            catch(ArithmeticException repeating){}return fraction();
        }
        String approximation(){return exact().contains("/")?new BigDecimal(numerator).divide(new BigDecimal(denominator),new MathContext(20,RoundingMode.HALF_EVEN)).stripTrailingZeros().toString():null;}
    }
    record Evaluation(Value value,boolean usedPrevious){}
    static Evaluation evaluate(String expression,Value previous){
        if(expression==null||expression.isBlank())throw reject(Problem.SYNTAX);
        if(expression.length()>128)throw reject(Problem.LIMIT);
        var parser=new Parser(expression.replace('×','*').replace('÷','/').replace('−','-'),previous);
        Value value=parser.sum();parser.space();if(parser.at!=parser.text.length())throw reject(Problem.SYNTAX);
        return new Evaluation(value,parser.usedPrevious);
    }
    private static final class Parser {
        final String text;final Value previous;int at,tokens,depth;boolean usedPrevious;
        Parser(String text,Value previous){this.text=text;this.previous=previous;}
        Value sum(){Value v=product();while(true){space();if(take('+'))v=v.add(product());else if(take('-'))v=v.add(product().negate());else return v;}}
        Value product(){Value v=unary();while(true){space();if(take('*'))v=v.multiply(unary());else if(take('/'))v=v.divide(unary());else return v;}}
        Value unary(){
            if(++depth>12||++tokens>96)throw reject(Problem.LIMIT);
            try{space();if(take('+'))return unary();if(take('-'))return unary().negate();return power();}finally{depth--;}
        }
        Value power(){Value v=atom();space();return take('^')?v.power(unary()):v;}
        Value atom(){
            space();if(take('(')){Value v=sum();space();if(!take(')'))throw reject(Problem.SYNTAX);return v;}
            if(text.startsWith("이전값",at)||text.startsWith("ans",at)){at+=3;usedPrevious=true;if(previous==null)throw reject(Problem.PREVIOUS);return previous;}
            int begin=at,digits=0;while(digit()){at++;digits++;}if(take('.'))while(digit()){at++;digits++;}
            if(digits==0)throw reject(Problem.SYNTAX);if(digits>32)throw reject(Problem.LIMIT);
            if(take('e')||take('E')){
                int exponent=at;if(!take('+'))take('-');int start=at;while(digit())at++;
                if(start==at)throw reject(Problem.SYNTAX);if(at-start>2)throw reject(Problem.LIMIT);
                if(Math.abs(Integer.parseInt(text.substring(exponent,at)))>30)throw reject(Problem.LIMIT);
            }
            BigDecimal n=new BigDecimal(text.substring(begin,at));int scale=n.scale();
            return scale>=0?new Value(n.unscaledValue(),BigInteger.TEN.pow(scale)):new Value(n.unscaledValue().multiply(BigInteger.TEN.pow(-scale)),BigInteger.ONE);
        }
        boolean digit(){return at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9';}
        void space(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}
        boolean take(char c){if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
    }
}
