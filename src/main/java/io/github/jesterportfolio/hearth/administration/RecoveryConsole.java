package io.github.jesterportfolio.hearth.administration;

import io.github.jesterportfolio.hearth.features.SceneContent;
import io.github.jesterportfolio.hearth.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 콘솔 전용 조회/비교. 대상은 기존 서버 연결에서만 해석하며 새 문맥·개인 행을 만들지 않습니다. */
public final class RecoveryConsole implements AutoCloseable {
    @FunctionalInterface public interface Reader {
        CompletionStage<RecoveryInspection.Report> prepare(ServerProgressAccess.Context context,UUID request,RecoveryInspection.Runtime before,Map<String,SceneContent.Scene> scenes);
    }
    public record Request(String action,UUID run,UUID player,UUID baseline,int page) {}
    private record Cached(ServerProgressAccess.Context context,RecoveryInspection.Report report,long expires) {}
    private final Reader reader;private final Supplier<List<ServerProgressAccess.Context>> links;
    private final Function<ServerProgressAccess.Context,RecoveryInspection.Runtime> runtime;
    private final Map<String,SceneContent.Scene> scenes;private final Executor owner;private final Runnable main;private final LongSupplier clock;
    private final LinkedHashMap<UUID,Cached> reports=new LinkedHashMap<>();
    private CompletionStage<Void> pending=CompletableFuture.completedFuture(null);private boolean busy,closed;

    public RecoveryConsole(Reader reader,Supplier<List<ServerProgressAccess.Context>> links,Function<ServerProgressAccess.Context,RecoveryInspection.Runtime> runtime,Map<String,SceneContent.Scene> scenes,Executor owner,Runnable main,LongSupplier clock) {
        this.reader=reader;this.links=links;this.runtime=runtime;this.scenes=Map.copyOf(scenes);this.owner=owner;this.main=main;this.clock=clock;
    }
    public static Request parse(boolean console,String[] args) {
        if(!console)throw new SecurityException("CONSOLE_ONLY");
        if(args.length==0||args.length==1&&args[0].equals("help"))return new Request("help",null,null,null,1);
        if(args[0].equals("inspect")&&args.length==3)return new Request("inspect",uuid(args[1]),uuid(args[2]),null,1);
        if(args[0].equals("compare")&&args.length==2)return new Request("compare",null,null,uuid(args[1]),1);
        if(args[0].equals("list")&&(args.length==1||args.length==2)) {
            int page=args.length==1?1:Integer.parseInt(args[1]);if(page<1||page>1000)throw new IllegalArgumentException("INSPECTION_PAGE");
            return new Request("list",null,null,null,page);
        }
        throw new IllegalArgumentException("INSPECTION_USAGE");
    }
    private static UUID uuid(String value){if(!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("INSPECTION_UUID");return UUID.fromString(value);}
    public CompletionStage<Void> command(boolean console,String[] args,Consumer<String> output) {
        main.run();Request request;
        try{request=parse(console,args);}catch(RuntimeException invalid){return CompletableFuture.failedFuture(invalid);}
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("INSPECTION_CLOSED"));
        if(busy)return CompletableFuture.failedFuture(new IllegalStateException("INSPECTION_BUSY"));
        prune();busy=true;
        try {
            CompletionStage<Void> work=switch(request.action()) {
                case "help" -> {
                    output.accept("hearthrecovery list [page] | inspect <run UUID> <player UUID> | compare <report UUID>");
                    output.accept("서버가 검증한 연결의 DB 확정 상태와 런타임을 읽습니다. 보고서는 10분/16개 보존합니다.");
                    yield CompletableFuture.completedFuture(null);
                }
                case "inspect" -> inspect(resolve(request.run(),request.player()),null,output,false);
                case "compare" -> {
                    var old=reports.get(request.baseline());if(old==null)throw new IllegalArgumentException("REPORT_ABSENT_OR_EXPIRED");
                    if(!verified(old.context()))throw new SecurityException("VERIFIED_LINK_CHANGED");
                    yield inspect(old.context(),old.report(),output,false);
                }
                case "list" -> {
                    var contexts=new TreeMap<String,ServerProgressAccess.Context>();
                    for(var c:links.get())contexts.put(c.storyRunId()+"/"+c.playerId(),c);
                    var values=List.copyOf(contexts.values());int pages=Math.max(1,(values.size()+5)/6);
                    if(request.page()>pages)throw new IllegalArgumentException("INSPECTION_PAGE");
                    output.accept("검증 연결 점검 "+request.page()+"/"+pages+" total="+values.size());
                    CompletionStage<Void> list=CompletableFuture.completedFuture(null);
                    for(var c:values.subList((request.page()-1)*6,Math.min(values.size(),request.page()*6)))list=list.thenComposeAsync(v->inspect(c,null,output,true),owner);
                    yield list;
                }
                default -> throw new IllegalStateException("INSPECTION_USAGE");
            };
            pending=work.whenCompleteAsync((v,error)->busy=false,owner);return pending;
        }catch(RuntimeException invalid){busy=false;return CompletableFuture.failedFuture(invalid);}
    }
    private ServerProgressAccess.Context resolve(UUID run,UUID player) {
        return links.get().stream().filter(c->c.storyRunId().equals(run)&&c.playerId().equals(player)).findFirst().orElseThrow(()->new SecurityException("UNKNOWN_VERIFIED_LINK"));
    }
    private boolean verified(ServerProgressAccess.Context context){return links.get().stream().anyMatch(c->c==context);}
    private CompletionStage<Void> inspect(ServerProgressAccess.Context context,RecoveryInspection.Report baseline,Consumer<String> output,boolean brief) {
        if(closed||!verified(context))return CompletableFuture.failedFuture(new SecurityException("VERIFIED_LINK_CHANGED"));
        var before=runtime.apply(context);UUID id=UUID.randomUUID();
        return reader.prepare(context,id,before,scenes).thenAcceptAsync(prepared->{
            main.run();if(closed)throw new IllegalStateException("INSPECTION_CLOSED");
            var after=runtime.apply(context);
            if(!verified(context)){var facts=new HashMap<>(after.facts());facts.put("verified_link","CHANGED");after=new RecoveryInspection.Runtime(after.world(),facts,after.holds(),after.pending());}
            var report=RecoveryInspection.observedAfter(prepared,after);
            while(reports.size()>=16)reports.remove(reports.keySet().iterator().next());
            reports.put(id,new Cached(context,report,clock.getAsLong()+600_000_000_000L));
            if(brief)output.accept("HEARTH_RECOVERY_REPORT "+report.summary());else RecoveryInspection.lines(report).forEach(output);
            if(baseline!=null){var diff=RecoveryInspection.compare(baseline,report);output.accept("HEARTH_RECOVERY_COMPARE baseline="+baseline.request()+" current="+id+" changed="+diff.changed()+" unconfirmed="+diff.unconfirmed());}
        },owner);
    }
    private void prune(){long now=clock.getAsLong();reports.values().removeIf(value->value.expires()-now<=0);}
    public static String errorCode(Throwable error){while(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();if(error instanceof StorageFailure failure)return failure.code();String code=error.getMessage();return code!=null&&code.matches("[A-Z][A-Z0-9_]{0,63}")?code:error.getClass().getSimpleName();}
    public Optional<RecoveryInspection.Report> report(UUID id){main.run();prune();return Optional.ofNullable(reports.get(id)).map(Cached::report);}
    public CompletionStage<Void> barrier(){main.run();return pending.handle((v,error)->null);}
    public void close(){main.run();closed=true;reports.clear();}
}
