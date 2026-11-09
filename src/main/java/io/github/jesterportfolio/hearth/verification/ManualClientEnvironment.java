package io.github.jesterportfolio.hearth.verification;

import java.util.Set;

/** 수동 UI 시험을 방해하는 자연 스폰만 제한하며 Hearth CUSTOM 스폰은 보존합니다. */
final class ManualClientEnvironment {
    private static final Set<String> NATURAL=Set.of("NATURAL","CHUNK_GEN","PATROL","REINFORCEMENTS","RAID","DEFAULT");
    static boolean blocksSpawn(String reason){return reason!=null&&NATURAL.contains(reason);}
}
