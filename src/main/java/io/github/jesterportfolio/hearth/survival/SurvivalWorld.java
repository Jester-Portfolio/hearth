package io.github.jesterportfolio.hearth.survival;

import java.util.*;

/** UI/명령이 좌표·파일 경로를 입력으로 받지 않는 고정 플레이 월드 구분입니다. */
public enum SurvivalWorld {
    RESIDENCE("residence","거주 월드","OAK_DOOR",11,"거주·건축을 위한 월드"),
    WILDERNESS("wilderness","야생 월드","GRASS_BLOCK",13,"탐험·채집을 위한 월드"),
    NETHER("nether","지옥 월드","NETHERRACK",15,"네더 탐험을 위한 월드");
    private final String id,label,material,description;private final int slot;
    SurvivalWorld(String id,String label,String material,int slot,String description){this.id=id;this.label=label;this.material=material;this.slot=slot;this.description=description;}
    public String id(){return id;}public String label(){return label;}public String material(){return material;}public int slot(){return slot;}public String description(){return description;}
    public static Optional<SurvivalWorld> at(int slot){return Arrays.stream(values()).filter(w->w.slot==slot).findFirst();}
    public static SurvivalWorld id(String id){return Arrays.stream(values()).filter(w->w.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("SURVIVAL_WORLD_UNKNOWN"));}
    public static Optional<SurvivalWorld> select(boolean current,boolean cancelled,boolean top,int slot,String click){return current&&!cancelled&&top&&"LEFT".equals(click)?at(slot):Optional.empty();}
}
