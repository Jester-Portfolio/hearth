package io.github.jesterportfolio.hearth.story;

import java.util.*;

/** 서버 설정으로 확정한 진행/차원/콘텐츠 연결. 클라이언트 입력으로 만들지 않습니다. */
public record StoryBinding(UUID run,String worldKey,UUID worldId,String templateVersion,String templateHash,String contentHash) {
    public StoryBinding {
        Objects.requireNonNull(run);Objects.requireNonNull(worldId);
        if(worldKey==null||!worldKey.matches("[a-z0-9_]+:[a-z0-9_]+")||worldKey.startsWith("minecraft:")
                ||templateVersion==null||!templateVersion.matches("[A-Za-z0-9_.-]{1,64}")
                ||templateHash==null||!templateHash.matches("[a-f0-9]{64}")||contentHash==null||!contentHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("STORY_BINDING_INVALID");
    }
}
