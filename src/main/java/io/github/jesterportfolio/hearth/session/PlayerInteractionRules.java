package io.github.jesterportfolio.hearth.session;

import java.util.*;

/** 기존 41슬롯의 기본 조작과 보관 슬롯 커서 조작을 허용합니다. 컨테이너 정책을 추가하지 않습니다. */
public final class PlayerInteractionRules {
    private PlayerInteractionRules(){}
    public record InventoryRequest(boolean active,boolean ownView,boolean ownInventory,boolean cursorEmpty,int slot,String click,String action,int hotbar){}
    public static boolean inventory(InventoryRequest r){
        if(!r.active()||!r.ownView()||!r.ownInventory()||r.slot()<0||r.slot()>40)return false;
        if(r.click().equals("LEFT")||r.click().equals("RIGHT"))return r.slot()<36&&switch(r.action()){
            case "PICKUP_ALL","PICKUP_SOME","PICKUP_HALF","PICKUP_ONE","PLACE_ALL","PLACE_SOME","PLACE_ONE","SWAP_WITH_CURSOR","NOTHING" -> true;
            default -> false;
        };
        if(!r.cursorEmpty())return false;
        return switch(r.click()){
            case "SHIFT_LEFT","SHIFT_RIGHT" -> r.action().equals("MOVE_TO_OTHER_INVENTORY");
            case "NUMBER_KEY" -> r.hotbar()>=0&&r.hotbar()<9&&Set.of("HOTBAR_SWAP","HOTBAR_MOVE_AND_READD").contains(r.action());
            case "SWAP_OFFHAND" -> r.action().equals("HOTBAR_SWAP");
            case "DROP" -> r.action().equals("DROP_ONE_SLOT");
            case "CONTROL_DROP" -> r.action().equals("DROP_ALL_SLOT");
            default -> false;
        };
    }
    public static boolean drag(boolean active,boolean ownView,Set<Integer> slots){return active&&ownView&&!slots.isEmpty()&&slots.stream().allMatch(i->i>=0&&i<36);}
    public static boolean facility(String material){return material.equals("LEVER")||material.endsWith("_BUTTON")||material.endsWith("_DOOR")||material.endsWith("_TRAPDOOR")||material.endsWith("_FENCE_GATE");}
    public static List<String> help(){return List.of(
        "격리 플레이 조작: 숫자키로 본인 슬롯/핫바 교환, Shift로 장비·보관 슬롯 이동, Q로 슬롯 아이템 버리기.",
        "보관 슬롯은 클릭으로 집기·나누기·합치기와 드래그를 지원합니다. 제작·상자/창고는 아직 지원하지 않습니다.",
        "문·울타리 문·다락문·버튼·레버는 WorldGuard 권한이 허용할 때 사용할 수 있습니다.",
        "개인 퀘스트: /hearthquest list · 공유 안내: /hearthstory guide · 스마트폰: /hearthphone",
        "본인에게 공개된 게임 정보 요약: /hearthinfo · 공개 코스 안내: /hearthjourney");}
}
