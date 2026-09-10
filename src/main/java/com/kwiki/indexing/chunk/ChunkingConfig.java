package com.kwiki.indexing.chunk;

/**
 * 分块尺寸配置。默认值遵循设计：父分块 1024-4096 字符，覆盖主要标题分区；
 * 子分块 128-512 字符（目标 384），并保持段落完整性。
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
