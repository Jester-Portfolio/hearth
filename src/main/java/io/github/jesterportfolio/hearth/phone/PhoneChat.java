package io.github.jesterportfolio.hearth.phone;

import java.util.*;
import java.util.concurrent.*;

/** owner 스레드에서만 사용하며 화면 종료와 AI 작업 수명을 분리합니다. 기록은 세션 메모리에만 둡니다. */
public final class PhoneChat {
    public record Turn(String question,String answer){}
    private final AssistantService service;private final Executor owner;private final List<Turn> history=new ArrayList<>();
    private CompletableFuture<String> pending;private String error;private String question;private final long timeoutMillis;private boolean closed;
    public PhoneChat(AssistantService service,Executor owner){this(service,owner,8000);}
    PhoneChat(AssistantService service,Executor owner,long timeoutMillis){this.service=service;this.owner=owner;this.timeoutMillis=timeoutMillis;}
    public String question(){return question;}
    public List<Turn> history(){return List.copyOf(history);}public boolean busy(){return pending!=null;}public String error(){return error;}
    public CompletionStage<Void> submit(String input){
        if(closed||busy())return CompletableFuture.failedFuture(new IllegalStateException("CHAT_BUSY_OR_CLOSED"));
        if(input==null||input.isBlank()||input.codePointCount(0,input.length())>256){error="질문은 1~256자로 입력해 주세요.";return CompletableFuture.failedFuture(new IllegalArgumentException("CHAT_INPUT_LENGTH"));}
        String question=input.strip();this.question=question;error=null;
        try{pending=service.ask(question).toCompletableFuture().orTimeout(timeoutMillis,TimeUnit.MILLISECONDS);}catch(RuntimeException e){error="AI 요청을 시작할 수 없습니다.";return CompletableFuture.failedFuture(e);}
        var work=pending;var result=new CompletableFuture<Void>();
        work.whenCompleteAsync((answer,failure)->{
            if(closed||pending!=work){result.completeExceptionally(new IllegalStateException("STALE_CHAT"));return;}
            pending=null;
            if(failure!=null||answer==null||answer.isBlank()){error="AI 응답 실패 또는 시간 초과입니다. 다시 시도해 주세요.";result.completeExceptionally(failure==null?new IllegalStateException("EMPTY_AI_RESPONSE"):failure);return;}
            int count=answer.codePointCount(0,answer.length());if(count>1200)answer=answer.substring(0,answer.offsetByCodePoints(0,1200))+"\n[응답 길이 제한]";
            history.add(new Turn(question,answer));while(history.size()>4)history.removeFirst();result.complete(null);
        },owner);return result;
    }
    public void close(){closed=true;if(pending!=null)pending.cancel(true);pending=null;history.clear();}
}
