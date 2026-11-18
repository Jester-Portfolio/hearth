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
    private static final String CALCULATION_FORMAT="{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160,\"pattern\":\"^[^\\\"\\\\r\\\\n]{1,154}\\\\[자료 2\\\\]$\"}},\"required\":[\"answer\"],\"additionalProperties\":false}";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    static String request(String prompt){return request(prompt,AssistantRequest.Purpose.PUBLIC_GAME);}
    static String request(AssistantRequest request){return request(request.prompt(),request.purpose());}
    private static String request(String prompt,AssistantRequest.Purpose purpose){
        unicode(prompt);String data=prompt.replace("<|","< |");if(data.getBytes(StandardCharsets.UTF_8).length>20000)throw failure(AssistantFailure.Reason.CONTEXT_LIMIT);
        String policy=switch(purpose){
            case SCIENCE_CALCULATION->"당신은 Hearth의 계산 설명 보조입니다. 제공된 과학 노트 [자료 1]과 서버 계산 기록 [자료 2]만 사용하세요. 입력값·식·정확값·단위·가정을 바꾸거나 임의로 재계산하지 마세요. 현재 질문에 직접 답하세요. 값/단위를 묻는 질문에만 계산 기록의 정확값/단위를 인용하고, 원리나 관계를 묻는 후속 질문에는 그 원리나 관계를 설명하세요. 질문과 관계없이 이전 정확값을 반복하지 마세요. 한국어 1~2문장, 160자 이내로 쓰며 입력 목록·풀이 나열·LaTeX는 생략하세요. 계산 근거 [자료 2]를 반드시 포함하고 원리는 [자료 1]도 붙이세요. 자료에 없는 게임 정보는 알 수 없다고 답하며 도구 실행·보상·게임 변경 권한은 없습니다.";
            case GENERAL_KNOWLEDGE->"당신은 Hearth의 지식 보조입니다. 한국어로 짧게 답하세요. 일반 과학·기술·수학의 원리를 설명할 수 있습니다. 답은 핵심만 한국어 1~2문장으로 쓰세요. 계산은 일반 텍스트 한 줄의 식과 결과만 쓰고 입력값 목록·풀이 단계·LaTeX·반복 설명은 생략하세요. 가정과 단위를 확인하고 확신하지 못하면 모른다고 답하세요. 현재 게임 상태·미발견 정보·개인 진행은 제공되지 않았으므로 추측하지 마세요. 자료 번호나 출처를 지어내지 마세요. 명령 실행이나 보상 지급 권한은 없습니다.";
            case SCIENCE_REFERENCE->"당신은 Hearth의 과학 자료 보조입니다. 선택한 과학 노트에 있는 원리·공식·가정만 한국어 1~2문장으로 설명하세요. 답변 본문에 반드시 [자료 1]을 붙이세요. 자료에 없는 원리·게임 진행·발견·제작 여부는 자료에 없어 알 수 없다고 답하고 [자료 1]을 붙이세요. 다른 자료 번호를 만들지 마세요. 수치 검산은 별도 계산기에 맡기며 명령 실행·도구 호출·게임 변경 권한은 없습니다.";
            case PUBLIC_GAME->"당신은 Hearth의 지식 보조입니다. 한국어로 짧게 답하세요. 제공된 공개 자료만 사용하고 모르는 게임 정보는 알 수 없다고 답하세요. 자료가 제공되면 답변 본문에 [자료 1]처럼 허용된 근거 표시를 각괄호까지 포함해 쓰세요. 자료가 없으면 번호를 만들지 마세요. 명령 실행이나 보상 지급 권한은 없습니다.";
        };
        if(purpose==AssistantRequest.Purpose.SCIENCE_CALCULATION)policy+=" JSON 객체 하나만 반환하세요. answer 문자열 본문에 근거 표시를 직접 쓰고 반드시 [자료 2]로 끝내세요. 줄바꿈·큰따옴표·입력식 재서술은 생략하세요. 다른 필드·마크다운 코드 블록·JSON 바깥 설명은 금지합니다. JSON 스키마: "+CALCULATION_FORMAT;
        String raw="<|im_start|>system\n"+policy+"<|im_end|>\n<|im_start|>user\n"+data+"<|im_end|>\n<|im_start|>assistant\n";
        var request=new JsonObject();request.addProperty("model",MODEL);request.addProperty("prompt",raw);request.addProperty("raw",true);request.addProperty("stream",false);request.addProperty("truncate",false);request.addProperty("shift",false);request.addProperty("keep_alive","60s");
        if(purpose==AssistantRequest.Purpose.SCIENCE_CALCULATION)request.add("format",object(CALCULATION_FORMAT));
        var options=new JsonObject();options.addProperty("num_ctx",CONTEXT);options.addProperty("num_predict",OUTPUT);options.addProperty("num_thread",2);options.addProperty("temperature",0);
        var stop=new JsonArray();stop.add("<|im_end|>");stop.add("<|endoftext|>");options.add("stop",stop);request.add("options",options);return JSON.toJson(request);
    }
    /** 경제 분류는 자유 문장을 파싱하지 않고 제한된 enum JSON만 요청합니다. 기존 질의 계약은 유지합니다. */
    static String classification(String prompt,List<String> choices){
        if(choices==null||choices.isEmpty()||choices.size()>3||new HashSet<>(choices).size()!=choices.size()||choices.stream().anyMatch(c->c==null||!c.matches("[A-Z][A-Z_]{0,15}")))throw new IllegalArgumentException("OLLAMA_CLASSIFICATION_CHOICES");
        var value=object(request(prompt));String raw="<|im_start|>system\n당신은 공개 시장 숫자를 분류하는 보조입니다. 주어진 후보 중 하나를 고르고 mode 필드 하나의 JSON 객체만 반환하세요. 설명·추론·도구 호출·게임 명령/상태 변경 권한은 없습니다.<|im_end|>\n<|im_start|>user\n"+prompt.replace("<|","< |")+"<|im_end|>\n<|im_start|>assistant\n";value.addProperty("prompt",raw);
        var schema=new JsonObject();schema.addProperty("type","object");var properties=new JsonObject();var mode=new JsonObject();mode.addProperty("type","string");var options=new JsonArray();choices.forEach(options::add);mode.add("enum",options);properties.add("mode",mode);schema.add("properties",properties);var required=new JsonArray();required.add("mode");schema.add("required",required);schema.addProperty("additionalProperties",false);value.add("format",schema);return JSON.toJson(value);
    }
    static String classified(String response,List<String> choices){var result=object(response);if(!result.keySet().equals(Set.of("mode")))throw invalid();String code=string(result,"mode");if(!choices.contains(code))throw invalid();return code;}
    static void version(String body){if(!VERSION.equals(string(object(body),"version")))throw failure(AssistantFailure.Reason.MODEL_MISMATCH);}
    static void model(String body){
        var root=object(body);if(!root.has("models")||!root.get("models").isJsonArray())throw invalid();int matches=0;
        for(var element:root.getAsJsonArray("models")){if(!element.isJsonObject())throw invalid();var model=element.getAsJsonObject();if(MODEL.equals(string(model,"name"))){if(!DIGEST.equals(string(model,"digest").replaceFirst("^sha256:","")))throw failure(AssistantFailure.Reason.MODEL_MISMATCH);matches++;}}
        if(matches!=1)throw failure(AssistantFailure.Reason.MODEL_MISMATCH);
    }
    static boolean loaded(String body){
        var root=object(body);if(!root.has("models")||!root.get("models").isJsonArray())throw invalid();var models=root.getAsJsonArray("models");if(models.isEmpty())return false;
        if(models.size()!=1)throw failure(AssistantFailure.Reason.MODEL_MISMATCH);if(!models.get(0).isJsonObject())throw invalid();var model=models.get(0).getAsJsonObject();
        if(!MODEL.equals(string(model,"name"))||!DIGEST.equals(string(model,"digest").replaceFirst("^sha256:",""))||integer(model,"context_length")!=CONTEXT)throw failure(AssistantFailure.Reason.MODEL_MISMATCH);return true;
    }
    static String preparation(){
        return request(preparationRequest());
    }
    private static AssistantRequest preparationRequest(){return AssistantRequest.science("Hearth AI 준비용 고정 요청: 작용 팔이 길어질 때 필요한 힘의 변화를 짧게 설명해.",List.of(),ScienceReading.LEVER);}
    static void prepared(String body){
        AssistantAnswer.present(preparationRequest(),answer(body));
    }
    /** 계산 설명만 정형 본문을 해석합니다. 누락된 자료 표시/틀린 JSON을 보정하거나 재요청하지 않습니다. */
    static String answer(String body,AssistantRequest request){
        String text=answer(body);if(request.purpose()!=AssistantRequest.Purpose.SCIENCE_CALCULATION)return text;
        var value=object(text);if(!value.keySet().equals(Set.of("answer")))throw invalid();
        String response=string(value,"answer");if(response.isBlank()||response.codePointCount(0,response.length())>160)throw invalid();
        String accepted=AssistantAnswer.present(request,response);if(!accepted.endsWith("[자료 2]"))throw failure(AssistantFailure.Reason.INVALID_REFERENCE);
        if(accepted.length()==6||accepted.indexOf('"')>=0||accepted.indexOf('\r')>=0||accepted.indexOf('\n')>=0)throw invalid();return accepted;
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
