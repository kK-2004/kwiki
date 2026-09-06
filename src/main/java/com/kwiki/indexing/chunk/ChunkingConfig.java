package com.kwiki.indexing.chunk;

/**
 * Chunk sizing configuration. Defaults follow the design: parents 1024-4096
 * characters over major heading sections, children 128-512 characters
 * (target 384) with paragraph integrity.
 */
public record ChunkingConfig(
        int parentMinChars,
        int parentMaxChars,
        int childMinChars,
        int childMaxChars,
        int childTargetChars) {

    public static ChunkingConfig defaults() {
        return new ChunkingConfig(1024, 4096, 128, 512, 384);
    }

    public ChunkingConfig {
        if (parentMinChars <= 0 || parentMaxChars < parentMinChars
                || childMinChars <= 0 || childMaxChars < childMinChars
                || childTargetChars < childMinChars || childTargetChars > childMaxChars) {
            throw new IllegalArgumentException("invalid chunking bounds");
        }
    }
}
