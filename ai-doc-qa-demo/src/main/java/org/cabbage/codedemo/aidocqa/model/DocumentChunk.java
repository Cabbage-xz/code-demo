package org.cabbage.codedemo.aidocqa.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/26
 */
@Data
@Builder
public class DocumentChunk {
    private String id;

    private String content;

    private String source;

    private Map<String, Object> metadata;
}
