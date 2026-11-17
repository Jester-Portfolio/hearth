package io.github.jesterportfolio.hearth.economy;

import io.github.jesterportfolio.hearth.gathering.*;
import java.util.*;

/** 공유 경제 채집의 검토 파일 지정. 정의·시설은 기존 채집 형식을 사용합니다. */
public record EconomyGatheringContent(GatheringContent gathering,GatheringFacilities facilities) {
    public EconomyGatheringContent{Objects.requireNonNull(gathering);Objects.requireNonNull(facilities);}
    public record File(String name,String sha256){public File{if(name==null||!name.matches("[A-Za-z0-9_-]+\\.yml")||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("ECON_GATHERING_FILE");}}
    public static Optional<File> file(Properties settings){String name=settings.getProperty("gathering-file"),hash=settings.getProperty("gathering-sha256");return name==null&&hash==null?Optional.empty():Optional.of(new File(name,hash));}
    public static EconomyGatheringContent parse(Map<String,?> root){if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1)throw new IllegalArgumentException("ECON_GATHERING_FORMAT");var gathering=GatheringContent.parse(section(root,"gathering"));return new EconomyGatheringContent(gathering,GatheringFacilities.parse(section(root,"facilities"),gathering));}
    @SuppressWarnings("unchecked") private static Map<String,Object> section(Map<String,?> root,String key){if(!(root.get(key) instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw new IllegalArgumentException("ECON_GATHERING_SECTION");return (Map<String,Object>)m;}
}
