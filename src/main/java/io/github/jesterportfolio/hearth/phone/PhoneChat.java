package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;

/** owner 스레드에서만 사용하며 화면 종료와 AI 작업 수명을 분리합니다. 기록은 세션 메모리에만 둡니다. */
public final class PhoneChat {
    public record Turn(String question,String answer){}
    public record DisplayTurn(Turn turn,String basis){}
    public record ScienceReference(String title,String context,int sources){}
    public static final class HistoryChoice {
        private final Exchange exchange;private final long revision;private final String basis;
        private HistoryChoice(Exchange exchange,long revision,String basis){this.exchange=exchange;this.revision=revision;this.basis=basis;}
        public Turn turn(){return exchange.turn();}public String basis(){return basis;}
    }
    private final AssistantService service;private final Executor owner;private final PhoneAnswerCache cache;private record Scope(String mode,String context,Object material){}private record Exchange(Turn turn,Scope scope){}
    private final List<Exchange> history=new ArrayList<>();private String lastMode="none";private String draft="";private long draftVersion,historyRevision;
    private static final class Work {
        final CompletableFuture<String> source,answer;final CompletableFuture<Void> result=new CompletableFuture<>();CompletableFuture<Boolean> validation;
        Work(CompletableFuture<String> source,long timeout){this.source=source;answer=source.copy().orTimeout(timeout,TimeUnit.MILLISECONDS);}
        void cancel(){answer.cancel(true);source.cancel(true);if(validation!=null)validation.cancel(true);}
    }
    private Work pending;private String error;private String question;private final long timeoutMillis;private boolean closed,retryable,includeHistory=true;
    public PhoneChat(AssistantService service,Executor owner){this(service,owner,8000);}
    PhoneChat(AssistantService service,Executor owner,long timeoutMillis){this(service,owner,timeoutMillis,new PhoneAnswerCache());}
    PhoneChat(AssistantService service,Executor owner,long timeoutMillis,PhoneAnswerCache cache){this.service=service;this.owner=owner;this.timeoutMillis=timeoutMillis;this.cache=cache;}
    public PhoneAnswerCache.Stats cacheStats(){return cache.stats();}
    public boolean calculationHelpAvailable(){return !closed&&service.allowsCalculationHelp();}
    public boolean includeHistory(){return includeHistory;}
    public void includeHistory(boolean value){if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");if(includeHistory!=value){includeHistory=value;cache.invalidate();}}
    public String draft(){return draft;}
    public void draft(String value){if(closed||value==null||value.codePointCount(0,value.length())>256)throw new IllegalArgumentException("CHAT_DRAFT_LENGTH");draft=value;draftVersion++;}
    public String question(){return question;}
    public List<Turn> history(){return history.stream().map(Exchange::turn).toList();}public boolean busy(){return pending!=null;}public String error(){return error;}
    private static String basis(Exchange e){return e.scope().mode().equals("research")&&e.scope().material() instanceof ScienceReading reading?"과학 자료 · "+reading.title():e.scope().mode().equals("calculation")&&e.scope().material() instanceof ScienceCalculationSnapshot calculation?"검산 계산 · "+calculation.label():PhoneContext.MODES.get(e.scope().mode());}
    public List<DisplayTurn> displayHistory(){return history.stream().map(e->new DisplayTurn(e.turn(),basis(e))).toList();}
    public List<HistoryChoice> review(){return history.stream().map(e->new HistoryChoice(e,historyRevision,basis(e))).toList();}
    private int historyIndex(HistoryChoice choice){if(choice!=null&&choice.revision==historyRevision)for(int i=0;i<history.size();i++)if(history.get(i)==choice.exchange)return i;return -1;}
    public Optional<ScienceReference> scienceReference(HistoryChoice choice){
        if(closed||historyIndex(choice)<0)throw new IllegalStateException("STALE_CHAT_HISTORY");var e=choice.exchange;
        if(e.scope().mode().equals("research")&&e.scope().material() instanceof ScienceReading reading&&e.scope().context().equals(reading.context()))return Optional.of(new ScienceReference(basis(e),e.scope().context(),1));
        if(e.scope().mode().equals("calculation")&&e.scope().material() instanceof ScienceCalculationSnapshot calculation&&e.scope().context().equals(calculation.context()))return Optional.of(new ScienceReference(basis(e),e.scope().context(),2));
        return Optional.empty();
    }
    public void rewind(HistoryChoice choice,boolean reuseQuestion){
        if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");
        int index=historyIndex(choice);
        if(index<0)throw new IllegalStateException("STALE_CHAT_HISTORY");
        history.subList(index,history.size()).clear();historyRevision++;cache.invalidate();error=null;retryable=false;question=null;
        if(reuseQuestion)draft(choice.turn().question());
    }
    private List<Turn> recent(Scope scope){return includeHistory?history.stream().filter(e->e.scope().equals(scope)).map(Exchange::turn).toList():List.of();}
    public CompletionStage<Void> submit(String input){return submit(input,"",()->true);}
    public CompletionStage<Void> submitScience(String input,ScienceReading reading,java.util.function.BooleanSupplier current){
        return submit(input,Objects.requireNonNull(reading).context(),"research",current,()->CompletableFuture.completedFuture(true),reading);
    }
    public CompletionStage<Void> submitCalculation(String input,ScienceCalculationSnapshot calculation,java.util.function.BooleanSupplier current){return submit(input,Objects.requireNonNull(calculation).context(),"calculation",current,()->CompletableFuture.completedFuture(true),calculation);}
    public AssistantRequest previewScience(String input,ScienceReading reading){
        return previewStudy(input,ScienceQuestion.reading(reading));
    }
    public CompletionStage<Void> submitSciencePreview(AssistantRequest shown,ScienceReading reading,java.util.function.BooleanSupplier current){
        return submitStudyPreview(shown,ScienceQuestion.reading(reading),current);
    }
    AssistantRequest previewStudy(String input,ScienceQuestion source){
        if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");return source.request(input,recent(new Scope(source.mode(),source.context(),source.material())));
    }
    CompletionStage<Void> submitStudyPreview(AssistantRequest shown,ScienceQuestion source,java.util.function.BooleanSupplier current){
        if(closed||busy())return CompletableFuture.failedFuture(new IllegalStateException("CHAT_BUSY_OR_CLOSED"));
        if(shown==null||!shown.equals(previewStudy(shown.question(),source))){error="대화나 선택한 자료가 변경됐습니다. 전송 내용을 다시 확인해 주세요.";retryable=false;return CompletableFuture.failedFuture(new IllegalStateException("STALE_SCIENCE_PREVIEW"));}
        return submit(shown.question(),source.context(),source.mode(),current,()->CompletableFuture.completedFuture(true),source.material());
    }
    public CompletionStage<Void> submit(String input,String context,java.util.function.BooleanSupplier current){return submit(input,context,context.isEmpty()?"none":"quests",current);}
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current){return submit(input,context,mode,current,()->CompletableFuture.completedFuture(true));}
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current,java.util.function.Supplier<CompletionStage<Boolean>> validate){
        return submit(input,context,mode,current,validate,null);
    }
    /** materialScope는 서버 내부의 불변 식별 표식이며 요청 프롬프트에는 포함하지 않습니다. */
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current,java.util.function.Supplier<CompletionStage<Boolean>> validate,Object materialScope){
        if(!PhoneContext.MODES.containsKey(mode)&&!"research".equals(mode)&&!"calculation".equals(mode))return CompletableFuture.failedFuture(new IllegalArgumentException("UNKNOWN_AI_CONTEXT"));
        if(closed||busy())return CompletableFuture.failedFuture(new IllegalStateException("CHAT_BUSY_OR_CLOSED"));
        if(input==null||input.isBlank()||input.codePointCount(0,input.length())>256){retryable=false;error="질문은 1~256자로 입력해 주세요.";return CompletableFuture.failedFuture(new IllegalArgumentException("CHAT_INPUT_LENGTH"));}
        var scope=mode.equals("none")?new Scope("none","",null):new Scope(mode,context,materialScope);
        String question=input.strip();draft(question);long expectedDraftVersion=draftVersion;this.question=question;lastMode=mode;error=null;retryable=false;
        final AssistantRequest request;final boolean fromCache;
        try{
            if(!current.getAsBoolean())throw new IllegalStateException("STALE_AI_CONTEXT");
            int sources=materialScope instanceof PhoneGrounding.Scope materials?materials.items().size():-1;
            var recent=recent(scope);
            if(mode.equals("none")&&(!context.isEmpty()||materialScope!=null))throw new IllegalArgumentException("GENERAL_KNOWLEDGE_HAS_GAME_CONTEXT");
            if(mode.equals("research")){
                if(!(materialScope instanceof ScienceReading reading)||!reading.context().equals(context))throw new IllegalArgumentException("UNAPPROVED_SCIENCE_REFERENCE");
                request=AssistantRequest.science(question,recent,reading);
            }else if(mode.equals("calculation")){
                if(!(materialScope instanceof ScienceCalculationSnapshot calculation)||!calculation.context().equals(context))throw new IllegalArgumentException("UNAPPROVED_CALCULATION_REFERENCE");request=AssistantRequest.calculation(question,recent,calculation);
            }else request=mode.equals("none")?AssistantRequest.general(question,recent):new AssistantRequest(question,recent,context,sources);
            if(mode.equals("calculation")&&!calculationHelpAvailable())throw new AssistantFailure(AssistantFailure.Reason.QUALITY_UNVERIFIED);
            cache.select(scope);var saved=service.allowsCache()?cache.find(request):Optional.<String>empty();
            fromCache=saved.isPresent();
            pending=new Work((saved.isPresent()?service.confirmCached(saved.orElseThrow()):service.ask(request)).toCompletableFuture(),timeoutMillis);
        }catch(RuntimeException e){cache.invalidate();retryable=true;error=AssistantFailure.message(e);return CompletableFuture.failedFuture(e);}
        var work=pending;var result=work.result;
        result.whenCompleteAsync((value,failure)->{if(result.isCancelled()&&pending==work)cancel();},owner);
        work.answer.whenCompleteAsync((answer,failure)->{
            if(stale(work)){if(pending==work)cancel();result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
            boolean approved;try{approved=current.getAsBoolean();}catch(RuntimeException stale){approved=false;}
            if(!approved){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보가 바뀌어 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
            if(failure!=null||answer==null||answer.isBlank()){cache.invalidate();pending=null;retryable=true;var rejected=failure==null?new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE):failure;error=AssistantFailure.message(rejected);work.cancel();result.completeExceptionally(rejected);return;}
            final String response;
            try{response=AssistantAnswer.present(request,answer);}catch(RuntimeException invalid){cache.invalidate();pending=null;retryable=true;error=AssistantFailure.message(invalid);work.cancel();result.completeExceptionally(invalid);return;}
            try{work.validation=validate.get().toCompletableFuture().copy().orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);}catch(RuntimeException invalid){work.validation=CompletableFuture.failedFuture(invalid);}
            work.validation.whenCompleteAsync((accepted,validationError)->{
                if(stale(work)){if(pending==work)cancel();result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
                boolean latest;try{latest=current.getAsBoolean();}catch(RuntimeException invalid){latest=false;}
                if(validationError!=null||!Boolean.TRUE.equals(accepted)||!latest){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보를 다시 확인하지 못해 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
                if(service.allowsCache()&&!fromCache)cache.put(request,answer);
                pending=null;
                if(draftVersion==expectedDraftVersion)draft("");
                history.add(new Exchange(new Turn(question,response),scope));while(history.size()>4)history.removeFirst();historyRevision++;result.complete(null);
            },owner);
        },owner);return result;
    }
    private boolean stale(Work work){return closed||pending!=work||work.result.isCancelled();}
    private void finishRejected(Work work,CompletableFuture<Void> result,String code,String message){cache.invalidate();if(pending==work)pending=null;retryable=true;error=message;work.cancel();result.completeExceptionally(new IllegalStateException(code));}
    public boolean canRetry(){return !closed&&!busy()&&retryable&&question!=null;}
    public CompletionStage<Void> retry(){return canRetry()&&lastMode.equals("none")?submit(question):CompletableFuture.failedFuture(new IllegalStateException("CHAT_RETRY_UNAVAILABLE"));}
    public void clear(){if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");cache.clear();history.clear();historyRevision++;question=null;error=null;retryable=false;draft("");}
    public void cancel(){
        if(closed||pending==null)return;var work=pending;pending=null;cache.invalidate();retryable=true;error="요청을 취소했습니다. 늦은 응답은 표시하지 않습니다.";work.result.cancel(false);work.cancel();
    }
    public void close(){closed=true;var work=pending;pending=null;cache.clear();if(work!=null){work.result.cancel(false);work.cancel();}history.clear();historyRevision++;draft="";draftVersion++;}
}
