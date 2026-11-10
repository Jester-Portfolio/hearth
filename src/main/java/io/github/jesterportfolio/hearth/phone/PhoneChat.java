package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;

/** owner 스레드에서만 사용하며 화면 종료와 AI 작업 수명을 분리합니다. 기록은 세션 메모리에만 둡니다. */
public final class PhoneChat {
    public record Turn(String question,String answer){}
    private final AssistantService service;private final Executor owner;private record Scope(String mode,String context,Object material){}private record Exchange(Turn turn,Scope scope){}
    private final List<Exchange> history=new ArrayList<>();private String lastMode="none";private String draft="";private long draftVersion;
    private static final class Work {
        final CompletableFuture<String> source,answer;final CompletableFuture<Void> result=new CompletableFuture<>();CompletableFuture<Boolean> validation;
        Work(CompletableFuture<String> source,long timeout){this.source=source;answer=source.copy().orTimeout(timeout,TimeUnit.MILLISECONDS);}
        void cancel(){answer.cancel(true);source.cancel(true);if(validation!=null)validation.cancel(true);}
    }
    private Work pending;private String error;private String question;private final long timeoutMillis;private boolean closed,retryable;
    public PhoneChat(AssistantService service,Executor owner){this(service,owner,8000);}
    PhoneChat(AssistantService service,Executor owner,long timeoutMillis){this.service=service;this.owner=owner;this.timeoutMillis=timeoutMillis;}
    public String draft(){return draft;}
    public void draft(String value){if(closed||value==null||value.codePointCount(0,value.length())>256)throw new IllegalArgumentException("CHAT_DRAFT_LENGTH");draft=value;draftVersion++;}
    public String question(){return question;}
    public List<Turn> history(){return history.stream().map(Exchange::turn).toList();}public boolean busy(){return pending!=null;}public String error(){return error;}
    public CompletionStage<Void> submit(String input){return submit(input,"",()->true);}
    public CompletionStage<Void> submit(String input,String context,java.util.function.BooleanSupplier current){return submit(input,context,context.isEmpty()?"none":"quests",current);}
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current){return submit(input,context,mode,current,()->CompletableFuture.completedFuture(true));}
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current,java.util.function.Supplier<CompletionStage<Boolean>> validate){
        return submit(input,context,mode,current,validate,null);
    }
    /** materialScope는 서버 내부의 불변 식별 표식이며 요청 프롬프트에는 포함하지 않습니다. */
    public CompletionStage<Void> submit(String input,String context,String mode,java.util.function.BooleanSupplier current,java.util.function.Supplier<CompletionStage<Boolean>> validate,Object materialScope){
        if(!PhoneContext.MODES.containsKey(mode))return CompletableFuture.failedFuture(new IllegalArgumentException("UNKNOWN_AI_CONTEXT"));
        if(closed||busy())return CompletableFuture.failedFuture(new IllegalStateException("CHAT_BUSY_OR_CLOSED"));
        if(input==null||input.isBlank()||input.codePointCount(0,input.length())>256){retryable=false;error="질문은 1~256자로 입력해 주세요.";return CompletableFuture.failedFuture(new IllegalArgumentException("CHAT_INPUT_LENGTH"));}
        var scope=mode.equals("none")?new Scope("none","",null):new Scope(mode,context,materialScope);
        String question=input.strip();draft(question);long expectedDraftVersion=draftVersion;this.question=question;lastMode=mode;error=null;retryable=false;
        try{if(!current.getAsBoolean())throw new IllegalStateException("STALE_AI_CONTEXT");pending=new Work(service.ask(new AssistantRequest(question,history.stream().filter(e->e.scope().equals(scope)).map(Exchange::turn).toList(),context)).toCompletableFuture(),timeoutMillis);}catch(RuntimeException e){retryable=true;error=AssistantFailure.message(e);return CompletableFuture.failedFuture(e);}
        var work=pending;var result=work.result;
        result.whenCompleteAsync((value,failure)->{if(result.isCancelled()&&pending==work)cancel();},owner);
        work.answer.whenCompleteAsync((answer,failure)->{
            if(stale(work)){if(pending==work)cancel();result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
            boolean approved;try{approved=current.getAsBoolean();}catch(RuntimeException stale){approved=false;}
            if(!approved){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보가 바뀌어 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
            if(failure!=null||answer==null||answer.isBlank()){pending=null;retryable=true;var rejected=failure==null?new AssistantFailure(AssistantFailure.Reason.INVALID_RESPONSE):failure;error=AssistantFailure.message(rejected);work.cancel();result.completeExceptionally(rejected);return;}
            try{work.validation=validate.get().toCompletableFuture().copy().orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);}catch(RuntimeException invalid){work.validation=CompletableFuture.failedFuture(invalid);}
            work.validation.whenCompleteAsync((accepted,validationError)->{
                if(stale(work)){if(pending==work)cancel();result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
                boolean latest;try{latest=current.getAsBoolean();}catch(RuntimeException invalid){latest=false;}
                if(validationError!=null||!Boolean.TRUE.equals(accepted)||!latest){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보를 다시 확인하지 못해 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
                pending=null;String response=answer;int count=response.codePointCount(0,response.length());if(count>1200)response=response.substring(0,response.offsetByCodePoints(0,1200))+"\n[응답 길이 제한]";
                if(draftVersion==expectedDraftVersion)draft("");
                history.add(new Exchange(new Turn(question,response),scope));while(history.size()>4)history.removeFirst();result.complete(null);
            },owner);
        },owner);return result;
    }
    private boolean stale(Work work){return closed||pending!=work||work.result.isCancelled();}
    private void finishRejected(Work work,CompletableFuture<Void> result,String code,String message){if(pending==work)pending=null;retryable=true;error=message;work.cancel();result.completeExceptionally(new IllegalStateException(code));}
    public boolean canRetry(){return !closed&&!busy()&&retryable&&question!=null;}
    public CompletionStage<Void> retry(){return canRetry()&&lastMode.equals("none")?submit(question):CompletableFuture.failedFuture(new IllegalStateException("CHAT_RETRY_UNAVAILABLE"));}
    public void clear(){if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");history.clear();question=null;error=null;retryable=false;draft("");}
    public void cancel(){
        if(closed||pending==null)return;var work=pending;pending=null;retryable=true;error="요청을 취소했습니다. 늦은 응답은 표시하지 않습니다.";work.result.cancel(false);work.cancel();
    }
    public void close(){closed=true;var work=pending;pending=null;if(work!=null){work.result.cancel(false);work.cancel();}history.clear();draft="";draftVersion++;}
}
