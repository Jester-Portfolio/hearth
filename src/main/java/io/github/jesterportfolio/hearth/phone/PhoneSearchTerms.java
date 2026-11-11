package io.github.jesterportfolio.hearth.phone;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 공개 문자열의 유한 검색 보조. 사전/외부 검색/모델 추측을 사용하지 않습니다. */
final class PhoneSearchTerms {
    private PhoneSearchTerms(){}
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Set<String> QUESTION=Set.of("어디","어디서","어떻게","무엇","뭐야","무슨","어떤","왜","알려줘","알려주세요","설명해","설명해주세요","있나요","있어","있을까","해주세요","대해서","대한","그리고");
    private static final List<String> PARTICLES=List.of("으로부터","에서부터","로부터","에게서","한테서","에서는","으로는","으로도","로는","로도","이랑","에서","에게","한테","까지","부터","처럼","보다","으로","에는","에도","와는","과는","하고","이나","로","나","이","가","을","를","은","는","의","에","와","과","도","만");
    record Term(String literal,String stem){}
    static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    static List<Term> parse(String question){
        var terms=new LinkedHashMap<String,Term>();var matches=WORD.matcher(normalize(question));
        while(matches.find()&&terms.size()<16){
            String literal=matches.group();int length=literal.codePointCount(0,literal.length());if(length<2||length>32)continue;
            String stem=literal;
            if(literal.matches("[가-힣]+"))for(String suffix:PARTICLES)if(literal.endsWith(suffix)&&literal.length()-suffix.length()>=2){stem=literal.substring(0,literal.length()-suffix.length());break;}
            if(!QUESTION.contains(literal)&&!QUESTION.contains(stem))terms.putIfAbsent(stem,new Term(literal,stem));
        }
        return List.copyOf(terms.values());
    }
    static int score(Term term,String title,String text){
        int score=contains(title,term.literal())?3:0;score+=contains(text,term.literal())?1:0;
        if(!term.stem().equals(term.literal()))score=Math.max(score,(contains(title,term.stem())?2:0)+(contains(text,term.stem())?1:0));
        return score;
    }
    private static boolean contains(String text,String term){
        if(term.matches("[가-힣]+"))return text.contains(term);
        int at=-1;while((at=text.indexOf(term,at+1))>=0){int end=at+term.length();if((at==0||!word(text.codePointBefore(at)))&&(end==text.length()||!word(text.codePointAt(end))))return true;}return false;
    }
    private static boolean word(int codepoint){return Character.isLetterOrDigit(codepoint)||codepoint=='_';}
}
