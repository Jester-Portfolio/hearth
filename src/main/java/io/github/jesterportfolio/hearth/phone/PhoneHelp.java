package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.*;
import java.util.*;

/** 선택한 한 항목의 공개 DTO만 AI 자료로 만듭니다. 내부 키는 프롬프트에 포함하지 않습니다. */
public final class PhoneHelp {
    private PhoneHelp(){}
    public record Selection(String section,String key,GameInformation selected,PhoneRetrieval.Source source){}
    public static Selection select(QuestCatalog catalog,QuestJournal.View view,GameInformation data,String section,int index){
        String key;GameInformation selected;
        switch(section){
            case "quests"->{key=PhoneQuestLinks.quests(catalog,view).get(index).id();selected=new GameInformation(List.of(data.quests().get(index)),List.of(),List.of(),List.of());}
            case "map"->{key=view.exploration().places().get(index).key();selected=new GameInformation(List.of(),List.of(data.places().get(index)),List.of(),List.of());}
            case "diary"->{var f=view.exploration().facts().get(index);key=(f.shared()?"S:":"P:")+f.id();selected=new GameInformation(List.of(),List.of(),List.of(data.diary().get(index)),List.of());}
            default->throw new IllegalArgumentException("HELP_SOURCE_UNAVAILABLE");
        }
        var detail=PhonePages.detail(data,section,index);return new Selection(section,key,selected,new PhoneRetrieval.Source(section,index,AssistantRequest.limit(detail.title(),64),AssistantRequest.limit(String.join(" · ",detail.pages()),400)));
    }
    public static Selection record(QuestCatalog catalog,QuestJournal.View view,GameInformation data,PhoneReaderLinks.Source source){
        if(!Set.of("STORY","PERSON","EXPLORATION").contains(source.section())||!PhoneReaderLinks.resolve(catalog,view,source.section(),source.key()).equals(source))throw new IllegalArgumentException("HELP_RECORD_UNAVAILABLE");
        for(int i=0;i<view.exploration().facts().size();i++){var f=view.exploration().facts().get(i);if(((f.shared()?"S:":"P:")+f.id()).equals(source.key()))return select(catalog,view,data,"diary",i);}throw new IllegalArgumentException("HELP_RECORD_UNAVAILABLE");
    }
    public static Selection refresh(QuestCatalog catalog,QuestJournal.View view,GameInformation data,Selection expected){
        var keys=switch(expected.section()){
            case "quests"->PhoneQuestLinks.quests(catalog,view).stream().map(PhoneQuestLinks.Quest::id).toList();
            case "map"->view.exploration().places().stream().map(p->p.key()).toList();
            case "diary"->view.exploration().facts().stream().map(f->(f.shared()?"S:":"P:")+f.id()).toList();
            default->throw new IllegalArgumentException("HELP_SOURCE_UNAVAILABLE");
        };int index=keys.indexOf(expected.key());if(index<0)throw new IllegalStateException("HELP_SOURCE_UNAVAILABLE");var current=select(catalog,view,data,expected.section(),index);if(!current.selected().equals(expected.selected()))throw new IllegalStateException("STALE_HELP_SOURCE");return current;
    }

    public static PhoneRetrieval.Result result(Selection selection){var source=selection.source();return new PhoneRetrieval.Result(List.of(source),"플레이어가 선택한 현재 공개 자료 한 항목입니다. 자료에 없는 스토리·진행·좌표는 추측하지 마세요. 게임 상태를 변경할 권한은 없습니다.\n[선택 자료] "+source.title()+"\n"+source.text());}
    public static boolean same(Selection first,Selection second){return first!=null&&second!=null&&first.section().equals(second.section())&&first.key().equals(second.key())&&first.selected().equals(second.selected());}
    public static String question(){return "이 자료를 바탕으로 현재 상황을 이해하고 다음 행동을 정리해 주세요.";}
}
