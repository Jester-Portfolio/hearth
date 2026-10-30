package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;
import java.util.regex.Pattern;

/** 공개 DTO 내부의 유한 키워드 검색. DB/원본 콘텐츠/다른 진행 검색이나 임베딩 서비스는 없습니다. */
public final class PhoneRetrieval {
    private PhoneRetrieval(){}
    public record Source(String section,int index,String title,String text){}
    public record Result(List<Source> sources,String context){public Result{sources=List.copyOf(sources);}}
    private record Ranked(Source source,int score){}
    public static Result find(GameInformation data,String mode,String question){
        if(!PhoneContext.MODES.containsKey(mode))throw new IllegalArgumentException("UNKNOWN_AI_CONTEXT");
        if(question==null||question.isBlank()||question.codePointCount(0,question.length())>256)throw new IllegalArgumentException("AI_QUESTION_LENGTH");
        if(mode.equals("none"))return new Result(List.of(),"");
        var tokens=new LinkedHashSet<String>();var matches=Pattern.compile("[\\p{L}\\p{N}_]{2,32}").matcher(question.toLowerCase(Locale.ROOT));while(matches.find()&&tokens.size()<16)tokens.add(matches.group());
        var candidates=new ArrayList<Source>();
        switch(mode){
            case "quests"->{for(int i=0;i<data.quests().size();i++){var q=data.quests().get(i);candidates.add(new Source(mode,i,q.title(),"NPC: "+q.npc()+" · "+q.status()+" · "+q.guidance()));}}
            case "map"->{for(int i=0;i<data.places().size();i++){var p=data.places().get(i);candidates.add(new Source(mode,i,p.name(),p.region()+" · "+p.status()+" · "+p.location()+(p.hubUnlocked()?" · 직접 방문 거점 해금":" · 거점 미해금")));}}
            case "diary"->{for(int i=0;i<data.diary().size();i++){var d=data.diary().get(i);candidates.add(new Source(mode,i,d.title(),(d.shared()?"공동체 기록 · 개인 완료/보상과 별개":"개인 기록")+" · "+d.category()+" · "+d.summary()+" · "+d.detail()));}}
            case "story"->{for(int i=0;i<data.story().size();i++)candidates.add(new Source(mode,i,"현재 공유 정보 "+(i+1),data.story().get(i)));}
            default->throw new IllegalArgumentException("UNKNOWN_AI_CONTEXT");
        }
        var ranked=new ArrayList<Ranked>();
        for(var source:candidates){String title=source.title().toLowerCase(Locale.ROOT),text=source.text().toLowerCase(Locale.ROOT);int score=0;for(String token:tokens){if(title.contains(token))score+=3;if(text.contains(token))score++;}if(score>0)ranked.add(new Ranked(source,score));}
        ranked.sort(Comparator.comparingInt(Ranked::score).reversed().thenComparingInt(r->r.source().index()));
        var sources=ranked.stream().limit(4).map(r->new Source(mode,r.source().index(),AssistantRequest.limit(r.source().title(),64),AssistantRequest.limit(r.source().text(),400))).toList();
        if(sources.isEmpty())return new Result(List.of(),"선택한 범위의 현재 공개 정보에서 질문과 일치하는 자료를 찾지 못했습니다. 미공개 스토리나 임의의 진행 사실을 추측하지 마세요.");
        var context=new StringBuilder("선택한 범위에서 현재 공개된 키워드 일치 자료입니다. 자료에 없는 스토리/진행 사실은 알 수 없다고 답하세요.\n");
        for(int i=0;i<sources.size();i++)context.append("[자료 ").append(i+1).append("] ").append(sources.get(i).title()).append('\n').append(sources.get(i).text()).append('\n');
        return new Result(sources,AssistantRequest.limit(context.toString(),2400));
    }
}
