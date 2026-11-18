package io.github.jesterportfolio.hearth.survival.market;

import io.github.jesterportfolio.hearth.economy.MarketAutomation.*;
import io.github.jesterportfolio.hearth.phone.*;
import java.util.*;
import java.util.concurrent.*;

/** 개인/계정/위치/스토리·명령 권한 없이 제한된 숫자 집계와 허용 모드만 전달합니다. */
public interface MarketAdviser extends AutoCloseable {
 CompletionStage<Mode> choose(Observation observation);
 CompletionStage<String> prepare();boolean prepared();
 @Override void close();
 static MarketAdviser ollama(String address){var backend=new OllamaAssistant(address);return new MarketAdviser(){public CompletionStage<Mode> choose(Observation o){return backend.classify(prompt(o),o.candidates().stream().map(Mode::name).toList()).thenApply(text->accept(text,o.candidates()));}public CompletionStage<String> prepare(){return backend.prepare();}public boolean prepared(){return backend.preparationRecorded();}public void close(){backend.close();}};}
 static String prompt(Observation o){return "경제 운송 분류 문제입니다. 게임 명령/변경 권한은 없고 서버가 후보를 재검증합니다. 다음 숫자는 공개 시장 집계이며 개인 정보가 없습니다. 현지 재고 부족 출발지="+o.sourcesLow()+", 부족 목적지="+o.destinationsLow()+", 가동 경로="+o.routesReady()+", 미충족 소비="+o.unmetDemand()+", 운송 중="+o.transit()+". 후보="+o.candidates()+". CONSERVE는 현지 재료 비축, RESTOCK은 부족 목적지 재입고, BALANCED는 균형입니다. 첫 후보는 서버 규칙의 우선안입니다. mode 필드에 적절한 후보 코드 하나만 담은 JSON 객체를 반환하세요. 설명/다른 필드/도구 호출은 금지합니다.";}
 static Mode accept(String response,List<Mode> allowed){if(response==null||response.length()>32)throw new IllegalArgumentException("MARKET_AI_RESPONSE");String text=response.strip();for(var mode:allowed)if(mode.name().equals(text))return mode;throw new IllegalArgumentException("MARKET_AI_RESPONSE");}
}
