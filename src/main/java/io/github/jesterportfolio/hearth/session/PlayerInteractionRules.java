package io.github.jesterportfolio.hearth.session;

import java.util.*;

/** 기존 41슬롯 안에서 커서를 만들지 않는 조작만 허용합니다. 컨테이너 정책을 추가하지 않습니다. */
public final class PlayerInteractionRules {
    private PlayerInteractionRules(){}
    public record InventoryRequest(boolean active,boolean ownView,boolean ownInventory,boolean cursorEmpty,int slot,String click,String action,int hotbar){}
    public static boolean inventory(InventoryRequest r){
        if(!r.active()||!r.ownView()||!r.ownInventory()||!r.cursorEmpty()||r.slot()<0||r.slot()>40)return false;
        return switch(r.click()){
            case "SHIFT_LEFT","SHIFT_RIGHT" -> r.action().equals("MOVE_TO_OTHER_INVENTORY");
            case "NUMBER_KEY" -> r.hotbar()>=0&&r.hotbar()<9&&Set.of("HOTBAR_SWAP","HOTBAR_MOVE_AND_READD").contains(r.action());
            case "SWAP_OFFHAND" -> r.action().equals("HOTBAR_SWAP");
            case "DROP" -> r.action().equals("DROP_ONE_SLOT");
            case "CONTROL_DROP" -> r.action().equals("DROP_ALL_SLOT");
            default -> false;
        };
    }
    public static boolean facility(String material){return material.equals("LEVER")||material.endsWith("_BUTTON")||material.endsWith("_DOOR")||material.endsWith("_TRAPDOOR")||material.endsWith("_FENCE_GATE");}
    public static List<String> help(){return List.of(
        "격리 플레이 조작: 숫자키로 본인 슬롯/핫바 교환, Shift로 장비·보관 슬롯 이동, Q로 슬롯 아이템 버리기.",
        "커서로 집기·드래그·제작·상자/창고 등 컨테이너는 아직 지원하지 않습니다. E키 기본 화면은 유지합니다.",
        "문·울타리 문·다락문·버튼·레버는 WorldGuard 권한이 허용할 때 사용할 수 있습니다.",
        "개인 퀘스트: /hearthquest list · 공유 안내: /hearthstory guide · 스마트폰: /hearthphone");}
}
