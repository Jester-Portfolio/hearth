package io.github.jesterportfolio.hearth.phone;

import java.util.regex.Pattern;

/** 서버가 전달한 자료 번호만 허용합니다. 내용의 정답/인용 충실도를 보증하는 검사는 아닙니다. */
public final class AssistantAnswer {
    private AssistantAnswer(){}
    private static final Pattern CITATION=Pattern.compile("\\[자료([^\\]]*)\\]");
    public static String present(AssistantRequest request,String answer){
        if(answer==null||answer.isBlank())throw new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE);
        int count=answer.codePointCount(0,answer.length());String visible=count>1200?answer.substring(0,answer.offsetByCodePoints(0,1200))+"\n[응답 길이 제한]":answer;
        if(request.sources()>=0){
            var all=CITATION.matcher(answer);int markers=0;while(all.find()){markers++;if(!permitted(all.group(1).strip(),request.sources()))throw invalid();}
            int opened=0,at=0;while((at=answer.indexOf("[자료",at))>=0){opened++;at+=3;}if(opened!=markers)throw invalid();
            if(request.sources()>0&&!CITATION.matcher(visible).find())throw invalid();
        }
        if(request.purpose()==AssistantRequest.Purpose.GENERAL_KNOWLEDGE)ArithmeticConsistency.check(visible);
        return visible;
    }
    private static boolean permitted(String value,int count){try{if(!value.matches("[0-9]+"))return false;int id=Integer.parseInt(value);return id>=1&&id<=count;}catch(NumberFormatException invalid){return false;}}
    private static AssistantFailure invalid(){return new AssistantFailure(AssistantFailure.Reason.INVALID_REFERENCE);}
}
