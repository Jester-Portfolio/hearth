package io.github.jesterportfolio.hearth.crafting;

import java.util.List;

/** 한 번 확인할 때의 제한된 제작 횟수입니다. 순정 결과 수량과 구분합니다. */
public final class CraftingBatch {
    public static final List<Integer> CHOICES = List.of(1, 4, 8);

    private CraftingBatch() {}

    public record Selection(String recipe, int times) {
        public Selection {
            if (recipe == null || !recipe.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("CRAFT_RECIPE");
            require(times);
        }
    }

    public static int require(int times) {
        if (!CHOICES.contains(times)) throw new IllegalArgumentException("CRAFT_BATCH_COUNT");
        return times;
    }
}
