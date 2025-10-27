package org.cabbage.codedemo.aidocqa.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @author xzcabbage
 * @since 2025/10/26
 * LangChain配置类
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class LangChainConfig {

    private final DeepSeekConfig deepSeekConfig;

    /**
     * DeepSeek 聊天模型配置
     *
     * @return dp聊天模型
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("初始化 DeepSeek 聊天模型，模型: {}", deepSeekConfig.getModel());
        return OpenAiChatModel.builder()
                .apiKey(deepSeekConfig.getApiKey())
                .baseUrl(deepSeekConfig.getBaseUrl())
                .modelName(deepSeekConfig.getModel())
                // 控制回答的"创造性"程度 取值范围：0.0 ~ 2.0
                // 0.0完全确定 每次相同问题给出相同答案
                // 1.0平衡创造性和准确性
                // 2.0高度随机、答案天马行空
                .temperature(deepSeekConfig.getTemperature())
                // 单次回答最大长度
                .maxTokens(deepSeekConfig.getMaxTokens())
                .timeout(Duration.ofSeconds(deepSeekConfig.getTimeout()))
                // 记录所有发送到DeepSeek的请求和收到的响应
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 使用 BGE 中文 Embedding 模型
     *
     * @return BGE 中文 Embedding 模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化中文 Embedding 模型: BGE-Small-ZH");
        return new BgeSmallZhEmbeddingModel();
    }
}
