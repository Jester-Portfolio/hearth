package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;

/** 질문에 맞는 공개 본문의 연속 구간 하나. 생략은 표시하며 문장/자료를 합성하지 않습니다. */
public final class PhoneExcerpt {
    private PhoneExcerpt(){}
    public static String select(String question,String text){return select(question,text,400);}
    public static String publicText(GameInformation data,String mode,int index,String question,String text){
        if(text.codePointCount(0,text.length())<=400)return text;
        String header=switch(mode){
            case "quests"->"상태: "+data.quests().get(index).status()+" · NPC: "+data.quests().get(index).npc();
            case "diary"->data.diary().get(index).shared()?"공동체 공유 기록 · 개인 완료/보상과 별개":"개인 기록";
            case "map"->"공개 상태: "+data.places().get(index).status()+" · "+(data.places().get(index).hubUnlocked()?"본인 방문 거점 해금":"빠른 이동 거점 미해금")+" · 이동은 시설에서 선택";
            case "story"->"공유 진행 · 개인 완료/보상과 별개";
            default->throw new IllegalArgumentException("UNKNOWN_AI_CONTEXT");
        };
        if(header.codePointCount(0,header.length())>96)header=AssistantRequest.limit(header,94)+" …";
        String prefix=header+"\n발췌:\n";return prefix+select(question,text,400-prefix.codePointCount(0,prefix.length()));
    }
    private static String select(String question,String text,int limit){
        int[] points=text.codePoints().toArray();if(points.length<=limit)return text;
        int width=limit-4;var terms=PhoneSearchTerms.parse(question);int best=0,end=width,score=0;
        if(!terms.isEmpty()){
            for(int from=0;from<points.length;from+=width/2){
                int to=Math.min(points.length,from+width),current=score(terms,new String(points,from,to-from));
                if(current>score){score=current;best=from;end=to;}
                if(to==points.length)break;
            }
            // 완전한 짧은 문단이 같은 근거를 담으면 불필요한 주변 내용을 줄입니다.
            for(int from=0;from<points.length;){int to=from;while(to<points.length&&points[to]!='\n')to++;
                if(to>from&&to-from<=width){int current=score(terms,new String(points,from,to-from));if(current>0&&(current>score||current==score&&to-from<end-best)){score=current;best=from;end=to;}}
                from=to+1;
            }
        }
        return (best>0?"… ":"")+new String(points,best,end-best)+(end<points.length?" …":"");
    }
    private static int score(java.util.List<PhoneSearchTerms.Term> terms,String text){int score=0;String normalized=PhoneSearchTerms.normalize(text);for(var term:terms)score+=PhoneSearchTerms.score(term,"",normalized);return score;}
}
