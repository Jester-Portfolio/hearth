package io.github.jesterportfolio.hearth.network;

import java.util.*;

/** 네트워크는 고정 로비↔경제 경로만 갖습니다. 주소·진행/개인 데이터는 메시지에 넣지 않습니다. */
public record NetworkSettings(UUID network,String server,String lobby,String economy,String forwardingEnvironment,String relayEnvironment) {
    public NetworkSettings {Objects.requireNonNull(network);for(var id:List.of(server,lobby,economy))if(!id.matches("[a-z][a-z0-9_-]{0,31}"))throw invalid();if(lobby.equals(economy)||!Set.of(lobby,economy).contains(server))throw invalid();for(var env:List.of(forwardingEnvironment,relayEnvironment))if(!env.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();if(forwardingEnvironment.equals(relayEnvironment))throw invalid();}
    public boolean lobbyRole(){return server.equals(lobby);}public String destination(){return lobbyRole()?economy:lobby;}
    public boolean edge(String from,String to){return from.equals(lobby)&&to.equals(economy)||from.equals(economy)&&to.equals(lobby);}
    public static NetworkSettings parse(Map<String,?> p){return new NetworkSettings(UUID.fromString(text(p,"network-id")),text(p,"server-id"),text(p,"lobby"),text(p,"economy"),text(p,"forwarding-secret-environment"),text(p,"relay-secret-environment"));}
    private static String text(Map<String,?> p,String key){if(!(p.get(key) instanceof String s)||s.isBlank())throw invalid();return s;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("NETWORK_CONFIG_INVALID");}
    public static byte[] secret(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("NETWORK_SECRET_REQUIRED");return HexFormat.of().parseHex(value);}
}
