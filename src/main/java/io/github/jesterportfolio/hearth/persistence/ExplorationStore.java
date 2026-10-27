package io.github.jesterportfolio.hearth.persistence;

import io.github.jesterportfolio.hearth.exploration.ExplorationContent;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

public interface ExplorationStore {
    record PlaceState(String id,Instant visited,Instant discovered,boolean hub,long revision){}
    record Fact(String id,String category,String title,String summary,String detail,int version,Instant first,Instant updated,boolean shared){}
    record Event(String id,int version,String category,String title,String summary,String detail,Instant time,boolean shared){}
    record Story(String stage,String title,Boolean explorationOpen,long revision){}
    record Data(Map<String,PlaceState> places,List<Fact> facts,List<Event> events,Story story){public Data{places=Map.copyOf(places);facts=List.copyOf(facts);events=List.copyOf(events);}}
    CompletionStage<Data> readExploration(ServerProgressAccess.Context ctx);
    CompletionStage<Boolean> recordPlace(ServerProgressAccess.Context ctx,ExplorationContent.Place place,ExplorationContent.Fact fact,boolean discover,boolean visit);
    CompletionStage<Boolean> recordFact(ServerProgressAccess.Context ctx,ExplorationContent.Fact fact,boolean shared);
    CompletionStage<Void> saveStory(ServerProgressAccess.Context ctx,Story expected,String stage,String title,Boolean open);
}
