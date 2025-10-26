package org.cabbage.codedemo.aidocqa.web.resp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author xzcabbage
 * @since 2025/10/26
 * 响应类
 */
@Data
@Builder
public class ChatResp {
    private String answer;

    private List<String> sources;

    private boolean modified;

    private int relevantDocsCount;
}
