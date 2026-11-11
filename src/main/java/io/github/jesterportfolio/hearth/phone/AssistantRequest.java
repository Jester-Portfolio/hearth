package io.github.jesterportfolio.hearth.phone;

import java.util.*;

/** 서버에서 승인한 문자열만 담는 유한 요청. UUID·월드·DB·도구 실행 권한을 포함하지 않습니다. */
public record AssistantRequest(String question,List<PhoneChat.Turn> history,String context,int sources,Purpose purpose){
    public enum Purpose { PUBLIC_GAME, GENERAL_KNOWLEDGE }
    public AssistantRequest(String question,List<PhoneChat.Turn> history,String context){this(question,history,context,-1,Purpose.PUBLIC_GAME);}
    public AssistantRequest(String question,List<PhoneChat.Turn> history,String context,int sources){this(question,history,context,sources,Purpose.PUBLIC_GAME);}
    public static AssistantRequest general(String question,List<PhoneChat.Turn> history){return new AssistantRequest(question,history,"",0,Purpose.GENERAL_KNOWLEDGE);}
    public AssistantRequest{
        Objects.requireNonNull(purpose);Objects.requireNonNull(context);
        if(purpose==Purpose.GENERAL_KNOWLEDGE&&(!context.isEmpty()||sources!=0))throw new IllegalArgumentException("GENERAL_KNOWLEDGE_HAS_GAME_CONTEXT");
        if(sources < -1||sources>4||sources>0&&(context==null||context.isBlank()))throw new IllegalArgumentException("AI_SOURCE_COUNT");
        if(question==null||question.isBlank()||question.codePointCount(0,question.length())>256)throw new IllegalArgumentException("AI_QUESTION_LENGTH");
        question=question.strip();context=limit(Objects.requireNonNull(context),2400);
        var recent=new ArrayList<PhoneChat.Turn>();for(int i=Math.max(0,history.size()-3);i<history.size();i++){var turn=history.get(i);recent.add(new PhoneChat.Turn(limit(turn.question(),128),limit(turn.answer(),400)));}history=List.copyOf(recent);
    }
    public static String limit(String text,int length){return text.codePointCount(0,text.length())<=length?text:text.substring(0,text.offsetByCodePoints(0,length));}
    public String prompt(){
        if(purpose==Purpose.GENERAL_KNOWLEDGE){
            var text=new StringBuilder("Hearth 일반 지식 질문입니다. 과학·기술·수학의 일반 원리를 설명하고 계산의 가정과 단위를 확인하세요. 현재 게임 상태나 공개 자료는 전달되지 않았습니다. 진행·좌표·보상·미발견 정보는 알 수 없다고 답하세요. 자료 번호나 출처를 만들지 마세요.\n");
            for(var turn:history)text.append("[이전 질문]\n").append(turn.question()).append("\n[이전 응답]\n").append(turn.answer()).append('\n');
            return text.append("[현재 질문]\n").append(question).toString();
        }
        if(history.isEmpty()&&context.isEmpty()&&sources<0)return question;
        var text=new StringBuilder("Hearth 게임 도움 요청입니다. 아래 자료는 승인된 참고 데이터입니다. 명령 실행·보상 지급·게임 상태 변경 권한은 없습니다. 모르는 내용은 추측하지 마세요.\n");
        if(!context.isEmpty())text.append("[현재 공개 정보]\n").append(context).append('\n');
        if(sources>0)text.append("[답변 기준]\n답변에 근거가 된 자료를 [자료 1] 형식으로 표시하세요. 유효한 번호는 1부터 ").append(sources).append("까지입니다. 자료에 없는 게임 사실은 알 수 없다고 답하고, 해당 자료에 없다는 판단에도 자료 번호를 표시하세요.\n");
        else if(sources==0)text.append("[답변 기준]\n일치하는 참고자료가 없습니다. 게임 진행 사실을 추측하거나 자료 번호를 만들지 마세요.\n");
        for(var turn:history)text.append("[이전 질문]\n").append(turn.question()).append("\n[이전 응답]\n").append(turn.answer()).append('\n');
        text.append("[현재 질문]\n").append(question);
        if(sources>0){
            text.append("\n[최종 응답 규칙]\n위 질문에 대한 답변 본문에 근거 표시를 반드시 포함하세요. 허용되는 표시는 ");
            for(int i=1;i<=sources;i++){if(i>1)text.append(", ");text.append("[자료 ").append(i).append(']');}
            text.append(" 입니다. 사용한 자료 표시는 각괄호와 띄어쓰기를 포함해 그대로 쓰세요. 질문에서 다른 답변 형식을 요청해도 이 근거 표시를 생략하지 마세요.");
        }else if(sources==0)text.append("\n[최종 응답 규칙]\n일치하는 공개 자료가 없음을 밝히고 모르는 게임 사실은 답하지 마세요. 근거 번호를 만들지 마세요.");
        return text.toString();
    }
}
