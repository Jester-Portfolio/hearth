package io.github.jesterportfolio.hearth.phone;

import io.github.jesterportfolio.hearth.quest.GameInformation;
import java.util.*;

/** 원본 콘텐츠 대신 기존 공개 정보 DTO만 소비합니다. 기본값은 게임 정보 미전달입니다. */
public final class PhoneContext {
    private PhoneContext(){}
    public static final Map<String,String> MODES;
    static {var modes=new LinkedHashMap<String,String>();modes.put("none","일반 질문 · 게임 정보 미전달");modes.put("quests","퀘스트 도움");modes.put("map","지도 도움");modes.put("diary","기록 도움");modes.put("story","공유 진행 도움");MODES=Collections.unmodifiableMap(modes);}
    public static String from(GameInformation data,String mode){
        if(!MODES.containsKey(mode))throw new IllegalArgumentException("UNKNOWN_AI_CONTEXT");if(mode.equals("none"))return "";
        return AssistantRequest.limit(String.join("\n",data.lines(new GameInformation.Query(mode,1))),2400);
    }
}
