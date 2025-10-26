package org.cabbage.codedemo.aidocqa.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author xzcabbage
 * @since 2025/10/26
 * 向量存储配置
 */
@Configuration
@Slf4j
public class VectorStoreConfig {

    /**
     * 内存向量存储
     * @return 内存向量存储
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("初始化内存向量存储");
        return new InMemoryEmbeddingStore<>();
    }
}
