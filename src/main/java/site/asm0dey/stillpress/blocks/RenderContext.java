package site.asm0dey.stillpress.blocks;

import module java.base;

public record RenderContext(Function<Long, Optional<String>> reusableBlockContent, int depth) {
    public RenderContext nested() {
        return new RenderContext(reusableBlockContent, depth + 1);
    }
}
