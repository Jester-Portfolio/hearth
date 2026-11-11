package io.github.jesterportfolio.hearth.phone;

import com.google.gson.*;
import io.github.jesterportfolio.hearth.verification.IsolatedPaths;
import java.nio.file.*;
import java.util.*;

/** 실제 기록 원문을 최종 표시 검사에 재생합니다. 모델 추론/실제 정확도 재시험은 아닙니다. */
public final class OllamaArithmeticReplay {
    public static void main(String[] args)throws Exception{
        Path input=Path.of(args[0]);IsolatedPaths.require(input.getParent(),"hearth-s5-trial.");Path output=IsolatedPaths.require(Path.of(args[1]),"hearth-s5-knowledge.").resolve("arithmetic-replay.json");
        var original=JsonParser.parseString(Files.readString(input)).getAsJsonObject();var rows=new JsonArray();boolean rejectedObserved=false;
        for(var item:original.getAsJsonArray("cases")){
            var prior=item.getAsJsonObject();String name=prior.get("name").getAsString(),answer=prior.get("answer").getAsString();var row=new JsonObject();row.addProperty("name",name);
            var request=name.equals("public_game_stays_grounded")?new AssistantRequest("자료 확인",List.of(),"합성 공개 기록",1):AssistantRequest.general("일반 질의 재생",List.of());
            try{AssistantAnswer.present(request,answer);row.addProperty("outcome","DISPLAY_CHECK_ACCEPTED");}
            catch(AssistantFailure failure){row.addProperty("outcome",failure.reason().name());if(name.equals("lever_100N")&&failure.reason()==AssistantFailure.Reason.INVALID_CALCULATION)rejectedObserved=true;}
            rows.add(row);
        }
        if(!rejectedObserved)throw new IllegalStateException("OBSERVED_CONTRADICTION_NOT_BLOCKED");
        var report=new JsonObject();report.addProperty("observed_contradiction_blocked",true);report.addProperty("new_model_inference",false);report.addProperty("general_correctness_proven",false);report.add("cases",rows);Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("HEARTH_S5_ARITHMETIC_REPLAY PASS");
    }
}
