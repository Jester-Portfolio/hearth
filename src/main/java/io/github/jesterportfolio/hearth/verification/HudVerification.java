package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.features.*;
import io.github.jesterportfolio.hearth.quest.PaperQuestUI;
import java.util.*;
import java.util.function.BiConsumer;
import org.bukkit.scoreboard.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.bossbar.BossBar;

/** 실제 Paper 표시 객체와 출력 소유권을 검사합니다. Player 대역은 만들지 않습니다. */
final class HudVerification {
    static void run(BiConsumer<Boolean,String> check) {
        var bars=new HashMap<String,List<BossBar>>();
        var boards=new HashMap<String,Scoreboard>();
        var delivery=new HudDelivery<String,List<String>>((p,v)->bars.put(p,PaperQuestUI.hudBars(v)),bars::remove);
        delivery.show("one",List.of("기존 의뢰"));
        check.accept(bars.get("one").size()==1,"hud_native_legacy_output_before_custom_registration");
        delivery.replace((p,v)->boards.put(p,FixedHud.board(v)),boards::remove);
        check.accept(bars.isEmpty(),"hud_native_custom_output_clears_owned_legacy_BossBar");
        var longLine="가".repeat(79)+"😀"+"잘려야 할 부분";
        delivery.show("one",List.of("현재 목표",longLine));
        delivery.show("two",List.of("다른 개인 목표"));
        var board=boards.get("one");
        check.accept(board.getObjective(DisplaySlot.SIDEBAR)!=null&&board.getTeams().size()==2&&bars.isEmpty(),"hud_native_single_sidebar_without_duplicate_quest_bar");
        var text=PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(board.getTeam("line1")).prefix());
        check.accept(text.equals("가".repeat(79)+"😀"),"hud_native_Korean_surrogate_pair_not_split_at_limit");
        var battle=BossBar.bossBar(net.kyori.adventure.text.Component.text("독립 보스 체력"),.5f,BossBar.Color.RED,BossBar.Overlay.PROGRESS);
        delivery.show("one",List.of("관전 중 실패 목표"));
        check.accept(PlainTextComponentSerializer.plainText().serialize(boards.get("one").getTeam("line0").prefix()).contains("관전"),"hud_native_readonly_projection_replaces_stale_goal");
        delivery.clear("one");
        check.accept(boards.containsKey("two")&&battle.progress()==.5f,"hud_native_clear_preserves_other_person_and_combat_channel");
        delivery.close();check.accept(boards.isEmpty(),"hud_native_shutdown_clears_all_owned_outputs");
    }
}
