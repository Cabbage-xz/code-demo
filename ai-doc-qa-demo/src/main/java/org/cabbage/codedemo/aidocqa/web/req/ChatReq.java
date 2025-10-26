package org.cabbage.codedemo.aidocqa.web.req;

import lombok.Data;

import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/26
 * 请求类
 */
@Data
public class ChatReq {
    private String question;

    private String userId;

    private Map<String, String> context;
}
