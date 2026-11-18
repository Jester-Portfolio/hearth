package io.github.jesterportfolio.hearth.survival.market;

import java.util.*;
import io.github.jesterportfolio.hearth.economy.MarketAutomation;

/** LLM 전송 설정은 경제 정책 해시에 포함하지 않아 AI를 제거해도 경제 상태를 유지할 수 있습니다. */
public record MarketSettings(MarketAutomation.Policy policy,boolean allowNew,String aiMode,String aiAddress){
 public MarketSettings{Objects.requireNonNull(policy);if(!Set.of("none","ollama").contains(aiMode)||aiAddress==null)throw new IllegalArgumentException("MARKET_AI_CONFIG");}
 private static int whole(Map<String,?> p,String key,int fallback){Object value=p.get(key);if(value==null)return fallback;if(!(value instanceof Integer n))throw new IllegalArgumentException("MARKET_CONFIG_INTEGER");return n;}
 public static MarketSettings parse(Map<String,?> p){Object allow=p.get("allow-new-controller");if(!(allow instanceof Boolean b))throw new IllegalArgumentException("MARKET_ALLOW_NEW_REQUIRED");return new MarketSettings(new MarketAutomation.Policy(whole(p,"interval-ticks",12),whole(p,"confirmations",2),whole(p,"minimum-hold-ticks",24),whole(p,"reserve-low-bps",1000),whole(p,"reserve-normal-bps",2500),whole(p,"reserve-high-bps",5000),whole(p,"buffer-ticks",2),whole(p,"low-stock-bps",5000)),b,p.get("ai-mode")==null?"none":String.valueOf(p.get("ai-mode")),p.get("ai-address")==null?"http://127.0.0.1:11435/api/generate":String.valueOf(p.get("ai-address")));}
}
