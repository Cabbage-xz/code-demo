package org.cabbage.codedemo.aidocqa.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author xzcabbage
 * @since 2025/10/26
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 为单个文本生成向量
     * @param text 文本
     * @return 向量
     */
    public Embedding embedText(String text) {
        log.debug("为文本生成向量，长度: {}", text.length());
        return embeddingModel.embed(text).content();
    }

    /**
     * 为多个文本生成向量
     * @param texts 多个文本
     * @return 向量
     */
    public List<Embedding> embedTexts(List<String> texts) {
        log.debug("批量生成向量，文本数量: {}", texts.size());
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();

        return embeddingModel.embedAll(segments).content();
    }
}
