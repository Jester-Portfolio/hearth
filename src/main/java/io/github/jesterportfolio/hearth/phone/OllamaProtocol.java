package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 승인한 모델/버전의 제한된 JSON 계약. 모델의 context·도구·임의 URL은 재사용하지 않습니다. */
final class OllamaProtocol {
    static final String VERSION="0.40.16",MODEL="qwen3:4b-instruct-2507-q4_K_M",DIGEST="0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0";
    static final int CONTEXT=4096,OUTPUT=128;
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    static String request(String prompt){
        unicode(prompt);if(prompt.getBytes(StandardCharsets.UTF_8).length>20000)throw failure(AssistantFailure.Reason.CONTEXT_LIMIT);
        String raw="<|im_start|>system\n당신은 Hearth의 지식 보조입니다. 한국어로 짧게 답하세요. 제공된 공개 자료만 사용하고 모르는 게임 정보는 알 수 없다고 답하세요. 자료가 제공되면 답변 본문에 [자료 1]처럼 허용된 근거 표시를 각괄호까지 포함해 쓰세요. 자료가 없으면 번호를 만들지 마세요. 명령 실행이나 보상 지급 권한은 없습니다.<|im_end|>\n<|im_start|>user\n"+prompt+"<|im_end|>\n<|im_start|>assistant\n";
        var request=new JsonObject();request.addProperty("model",MODEL);request.addProperty("prompt",raw);request.addProperty("raw",true);request.addProperty("stream",false);request.addProperty("truncate",false);request.addProperty("shift",false);request.addProperty("keep_alive","60s");
        var options=new JsonObject();options.addProperty("num_ctx",CONTEXT);options.addProperty("num_predict",OUTPUT);options.addProperty("num_thread",2);options.addProperty("temperature",0);
        var stop=new JsonArray();stop.add("<|im_end|>");stop.add("<|endoftext|>");options.add("stop",stop);request.add("options",options);return JSON.toJson(request);
    }
    static void version(String body){if(!VERSION.equals(string(object(body),"version")))throw failure(AssistantFailure.Reason.MODEL_MISMATCH);}
    static void model(String body){
        var root=object(body);if(!root.has("models")||!root.get("models").isJsonArray())throw invalid();int matches=0;
        for(var element:root.getAsJsonArray("models")){if(!element.isJsonObject())throw invalid();var model=element.getAsJsonObject();if(MODEL.equals(string(model,"name"))){if(!DIGEST.equals(string(model,"digest").replaceFirst("^sha256:","")))throw failure(AssistantFailure.Reason.MODEL_MISMATCH);matches++;}}
        if(matches!=1)throw failure(AssistantFailure.Reason.MODEL_MISMATCH);
    }
    static String answer(String body){
        var root=object(body);if(root.has("error")||!MODEL.equals(string(root,"model")))throw invalid();
        if(!root.has("done")||!root.get("done").isJsonPrimitive()||!root.getAsJsonPrimitive("done").isBoolean()||!root.get("done").getAsBoolean())throw invalid();
        String reason=string(root,"done_reason");if(reason.equals("length"))throw failure(AssistantFailure.Reason.OUTPUT_LIMIT);if(!reason.equals("stop"))throw invalid();
        int prompt=integer(root,"prompt_eval_count"),output=integer(root,"eval_count");if(prompt<1||prompt>CONTEXT-OUTPUT)throw failure(AssistantFailure.Reason.CONTEXT_LIMIT);if(output<1||output>OUTPUT)throw invalid();
        if(root.has("thinking")&&!string(root,"thinking").isEmpty()||root.has("tool_calls"))throw invalid();
        String answer=string(root,"response");if(answer.isBlank()||answer.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();return answer;
    }
    static AssistantFailure failure(AssistantFailure.Reason reason){return new AssistantFailure(reason);}static AssistantFailure invalid(){return failure(AssistantFailure.Reason.INVALID_RESPONSE);}
    private static String string(JsonObject root,String key){if(!root.has(key)||!root.get(key).isJsonPrimitive()||!root.getAsJsonPrimitive(key).isString())throw invalid();return root.get(key).getAsString();}
    private static int integer(JsonObject root,String key){try{if(!root.has(key)||!root.get(key).isJsonPrimitive()||!root.getAsJsonPrimitive(key).isNumber())throw invalid();return root.get(key).getAsBigDecimal().intValueExact();}catch(ArithmeticException invalid){throw invalid();}}
    private static JsonObject object(String text){
        try(var reader=new JsonReader(new StringReader(text))){reader.setStrictness(Strictness.STRICT);var value=read(reader,0);if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw invalid();return value.getAsJsonObject();}catch(IOException|IllegalArgumentException malformed){throw invalid();}
    }
    private static JsonElement read(JsonReader reader,int depth)throws IOException{
        if(depth>16)throw invalid();return switch(reader.peek()){
            case BEGIN_OBJECT->{var value=new JsonObject();reader.beginObject();while(reader.hasNext()){String key=reader.nextName();unicode(key);if(value.has(key))throw invalid();value.add(key,read(reader,depth+1));}reader.endObject();yield value;}
            case BEGIN_ARRAY->{var value=new JsonArray();reader.beginArray();while(reader.hasNext()){if(value.size()>=4096)throw invalid();value.add(read(reader,depth+1));}reader.endArray();yield value;}
            case STRING->{String value=reader.nextString();unicode(value);yield new JsonPrimitive(value);}
            case NUMBER->{String number=reader.nextString();if(number.length()>24)throw invalid();yield new JsonPrimitive(new BigDecimal(number));}
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw invalid();
        };
    }
    private static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}}
}
