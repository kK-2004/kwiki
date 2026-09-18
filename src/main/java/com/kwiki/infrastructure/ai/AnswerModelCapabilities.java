package com.kwiki.infrastructure.ai;

/** Provider 在应用初始化时公开的回答模型能力。null 表示由 provider 使用其默认上限。 */
public record AnswerModelCapabilities(String model, Integer maxOutputTokens, String source) {
}
