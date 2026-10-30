package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;

/** owner 스레드에서만 사용하며 화면 종료와 AI 작업 수명을 분리합니다. 기록은 세션 메모리에만 둡니다. */
public final class PhoneChat {
    public record Turn(String question,String answer){}
    private final AssistantService service;private final Executor owner;private record Exchange(Turn turn,String scope){}
    private final List<Exchange> history=new ArrayList<>();private String lastMode="none";private String draft="";private long draftVersion;
    private CompletableFuture<String> pending;private String error;private String question;private final long timeoutMillis;private boolean closed,retryable;
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
        if(!PhoneContext.MODES.containsKey(mode))return CompletableFuture.failedFuture(new IllegalArgumentException("UNKNOWN_AI_CONTEXT"));
        if(closed||busy())return CompletableFuture.failedFuture(new IllegalStateException("CHAT_BUSY_OR_CLOSED"));
        if(input==null||input.isBlank()||input.codePointCount(0,input.length())>256){retryable=false;error="질문은 1~256자로 입력해 주세요.";return CompletableFuture.failedFuture(new IllegalArgumentException("CHAT_INPUT_LENGTH"));}
        String scope=mode.equals("none")?"none":mode+"\u0000"+context;
        String question=input.strip();draft(question);long expectedDraftVersion=draftVersion;this.question=question;lastMode=mode;error=null;retryable=false;
        try{if(!current.getAsBoolean())throw new IllegalStateException("STALE_AI_CONTEXT");pending=service.ask(new AssistantRequest(question,history.stream().filter(e->e.scope().equals(scope)).map(Exchange::turn).toList(),context)).toCompletableFuture().orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);}catch(RuntimeException e){retryable=true;error="AI 요청을 시작할 수 없습니다.";return CompletableFuture.failedFuture(e);}
        var work=pending;var result=new CompletableFuture<Void>();
        work.whenCompleteAsync((answer,failure)->{
            if(closed||pending!=work){result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
            boolean approved;try{approved=current.getAsBoolean();}catch(RuntimeException stale){approved=false;}
            if(!approved){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보가 바뀌어 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
            if(failure!=null||answer==null||answer.isBlank()){pending=null;retryable=true;error="AI 응답 실패 또는 시간 초과입니다. 다시 시도해 주세요.";result.completeExceptionally(failure==null?new IllegalStateException("EMPTY_AI_RESPONSE"):failure);return;}
            CompletionStage<Boolean> validation;try{validation=validate.get().toCompletableFuture().orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);}catch(RuntimeException invalid){validation=CompletableFuture.failedFuture(invalid);}
            validation.whenCompleteAsync((accepted,validationError)->{
                if(closed||pending!=work){result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
                boolean latest;try{latest=current.getAsBoolean();}catch(RuntimeException invalid){latest=false;}
                if(validationError!=null||!Boolean.TRUE.equals(accepted)||!latest){finishRejected(work,result,"STALE_AI_CONTEXT","현재 게임 정보를 다시 확인하지 못해 응답을 표시하지 않습니다. 최신 정보로 다시 요청해 주세요.");return;}
                pending=null;String response=answer;int count=response.codePointCount(0,response.length());if(count>1200)response=response.substring(0,response.offsetByCodePoints(0,1200))+"\n[응답 길이 제한]";
                if(draftVersion==expectedDraftVersion)draft("");
                history.add(new Exchange(new Turn(question,response),scope));while(history.size()>4)history.removeFirst();result.complete(null);
            },owner);
        },owner);return result;
    }
    private void finishRejected(CompletableFuture<String> work,CompletableFuture<Void> result,String code,String message){if(pending==work)pending=null;retryable=true;error=message;result.completeExceptionally(new IllegalStateException(code));}
    public boolean canRetry(){return !closed&&!busy()&&retryable&&question!=null;}
    public CompletionStage<Void> retry(){return canRetry()&&lastMode.equals("none")?submit(question):CompletableFuture.failedFuture(new IllegalStateException("CHAT_RETRY_UNAVAILABLE"));}
    public void clear(){if(closed||busy())throw new IllegalStateException("CHAT_BUSY_OR_CLOSED");history.clear();question=null;error=null;retryable=false;draft("");}
    public void cancel(){
        if(closed||pending==null)return;var work=pending;pending=null;retryable=true;error="요청을 취소했습니다. 늦은 응답은 표시하지 않습니다.";work.cancel(true);
    }
    public void close(){closed=true;if(pending!=null)pending.cancel(true);pending=null;history.clear();draft="";draftVersion++;}
}
