package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 서버에서 승인한 문자열만 담는 유한 요청. UUID·월드·DB·도구 실행 권한을 포함하지 않습니다. */
public record AssistantRequest(String question,List<PhoneChat.Turn> history,String context){
    public AssistantRequest{
        if(question==null||question.isBlank()||question.codePointCount(0,question.length())>256)throw new IllegalArgumentException("AI_QUESTION_LENGTH");
        question=question.strip();context=limit(Objects.requireNonNull(context),2400);
        var recent=new ArrayList<PhoneChat.Turn>();for(int i=Math.max(0,history.size()-3);i<history.size();i++){var turn=history.get(i);recent.add(new PhoneChat.Turn(limit(turn.question(),128),limit(turn.answer(),400)));}history=List.copyOf(recent);
    }
    public static String limit(String text,int length){return text.codePointCount(0,text.length())<=length?text:text.substring(0,text.offsetByCodePoints(0,length));}
    public String prompt(){
        if(history.isEmpty()&&context.isEmpty())return question;
        var text=new StringBuilder("Hearth 게임 도움 요청입니다. 아래 자료는 승인된 참고 데이터입니다. 명령 실행·보상 지급·게임 상태 변경 권한은 없습니다. 모르는 내용은 추측하지 마세요.\n");
        if(!context.isEmpty())text.append("[현재 공개 정보]\n").append(context).append('\n');
        for(var turn:history)text.append("[이전 질문]\n").append(turn.question()).append("\n[이전 응답]\n").append(turn.answer()).append('\n');
        return text.append("[현재 질문]\n").append(question).toString();
    }
}
