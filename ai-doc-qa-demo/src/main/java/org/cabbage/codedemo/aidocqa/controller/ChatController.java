package org.cabbage.codedemo.aidocqa.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.aidocqa.common.Result;
import org.cabbage.codedemo.aidocqa.service.AiChatService;
import org.cabbage.codedemo.aidocqa.service.DocumentService;
import org.cabbage.codedemo.aidocqa.web.req.ChatReq;
import org.cabbage.codedemo.aidocqa.web.resp.ChatResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/26
 */
@RestController
@RequestMapping("/api/ai")
@Slf4j
@RequiredArgsConstructor
public class ChatController {
    private final AiChatService aiChatService;
    private final DocumentService documentService;

    @Value("${document.base-path}")
    private String documentBasePath;

    /**
     * 检查系统
     * @return 系统运行状态
     */
    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "系统运行正常");
        return Result.success(result);
    }


    /**
     * 聊天
     * @param req 请求
     * @return 结果
     */
    @PostMapping("/chat")
    public Result<ChatResp> chat(@RequestBody ChatReq req) {
        try {
            log.info("收到聊天请求: {}", req.getQuestion());
            ChatResp response = aiChatService.chat(req);
            return Result.success(response);
        } catch (Exception e) {
            log.error("处理聊天请求失败", e);
            return Result.error("抱歉，处理您的请求时出现错误: " + e.getMessage());
        }
    }

    /**
     * 加载文档
     * @param path 路径
     * @return 加载结果
     */
    @PostMapping("/document/load")
    public Result<Map<String, String>> loadDocument(@RequestParam(required = false) String path) {
        try {
            String targetPath = path != null ? path : documentBasePath;
            log.info("开始加载文档，路径: {}", targetPath);
            documentService.loadDocuments(targetPath);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "文档加载成功");
            response.put("path", targetPath);

            return Result.success(response);
        } catch (Exception e) {
            log.error("加载文档失败", e);
            return Result.error("文档加载失败: " + e.getMessage());
        }
    }

    /**
     * 清空向量库
     * @return 结果
     */
    @PostMapping("/documents/clear")
    public Result<Map<String, String>> clearDocument() {
        try {
            documentService.clearStore();
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "向量库已清空");
            return Result.success(response);
        } catch (Exception e) {
            log.error("清空向量库失败", e);
            return Result.error("清空失败: " + e.getMessage());
        }
    }

}
