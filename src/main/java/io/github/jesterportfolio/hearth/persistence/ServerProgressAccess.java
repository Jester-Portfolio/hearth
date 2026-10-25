package io.github.jesterportfolio.hearth.persistence;

import java.util.Objects;
import java.util.UUID;

/** 서버 내부의 검증된 진행 연결만 전달합니다. 네트워크/명령/물리 월드 ID 입력 경로가 아닙니다. */
public final class ServerProgressAccess {
    public static final class Context {
        private final ServerProgressAccess issuer;
        private final UUID storyRunId;
        private final UUID playerId;
        private Context(ServerProgressAccess issuer, UUID run, UUID player) {
            this.issuer = issuer;
            this.storyRunId = Objects.requireNonNull(run);
            this.playerId = Objects.requireNonNull(player);
        }
        public UUID storyRunId() { return storyRunId; }
        public UUID playerId() { return playerId; }
    }

    /** 신뢰하는 서버 부트스트랩 전용. 클라이언트가 보낸 진행 ID로 호출하면 안 됩니다. */
    public Context forVerifiedServerLink(UUID storyRunId, UUID playerId) {
        return new Context(this, storyRunId, playerId);
    }

    void requireIssued(Context context) {
        if (context == null || context.issuer != this) throw new SecurityException("Untrusted progress context");
    }
}
