package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.persistence.*;
import io.github.jesterportfolio.hearth.administration.*;
import io.github.jesterportfolio.hearth.features.SceneContent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.sql.DataSource;

/** 정상 실행/HOLD에서 같은 읽기 전용 콘솔. 자동 수리·HOLD 해제·행 생성은 없습니다. */
public final class StoryInspection implements AutoCloseable {
    private final PostgresProgressStore database;private final StoryInspectionLinks links;private final RecoveryConsole console;private final Executor owner;private final Runnable main;
    private boolean busy,closed;private CompletionStage<Void> pending=CompletableFuture.completedFuture(null);
    public StoryInspection(DataSource source,StoryRuntimeSettings settings,Map<String,SceneContent.Scene> scenes,Function<ServerProgressAccess.Context,RecoveryInspection.Runtime> runtime,Executor owner,Runnable main,Consumer<PostgresProgressStore.Failure> failures){
        this.owner=owner;this.main=main;var access=new ServerProgressAccess();database=PostgresProgressStore.forReadOnlyModules(source,access,failures);links=new StoryInspectionLinks(settings,database,access);var store=new InspectionStore(database,access);console=new RecoveryConsole(store::prepare,links::contexts,runtime,scenes,owner,main,System::nanoTime);
    }
    public CompletionStage<Void> command(boolean isConsole,String[] args,Consumer<String> output){
        main.run();try{var request=RecoveryConsole.parse(isConsole,args);if(closed||busy)throw new IllegalStateException("STORY_INSPECTION_BUSY_OR_CLOSED");if(request.action().equals("help"))return console.command(true,args,output);
            busy=true;pending=links.read().thenComposeAsync(rows->{if(closed)throw new IllegalStateException("INSPECTION_CLOSED");links.replace(rows);return console.command(true,args,output);},owner).whenCompleteAsync((v,error)->busy=false,owner);return pending;
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
    public CompletionStage<Void> shutdown(){main.run();close();var closing=database.closeAsync();return CompletableFuture.allOf(pending.handle((v,error)->null).toCompletableFuture(),closing.toCompletableFuture());}
    @Override public void close(){main.run();if(closed)return;closed=true;console.close();links.clear();}
}
