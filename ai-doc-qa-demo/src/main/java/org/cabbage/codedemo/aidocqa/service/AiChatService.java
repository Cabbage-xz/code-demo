package org.cabbage.codedemo.aidocqa.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.aidocqa.web.req.ChatReq;
import org.cabbage.codedemo.aidocqa.web.resp.ChatResp;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author xzcabbage
 * @since 2025/10/26
 * AI聊天
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {
    private final ChatLanguageModel chatModel;
    private final DocumentService documentService;

    /**
     * 处理聊天请求
     * @param req 请求
     * @return 响应
     */
    public ChatResp chat(ChatReq req) {
        log.info("收到问题: {}, 用户: {}", req.getQuestion(), req.getUserId());
        // 检查特殊规则
        String specialAnswer = applySpecialRules(req);
        if (specialAnswer != null) {
            log.info("应用特殊规则，直接返回答案");
            return ChatResp.builder()
                    .answer(specialAnswer)
                    .modified(true)
                    .relevantDocsCount(0)
                    .build();
        }
        // 搜索相关文档
        List<TextSegment> relevantDocs = documentService.searchRelevantDocuments(req.getQuestion());
        log.info("找到 {} 个相关文档块", relevantDocs.size());

        // 构建提示词
        String prompt = buildPrompt(req.getQuestion(), relevantDocs, req.getContext());
        // 调用DeepSeek生成答案
        String answer = chatModel.generate(prompt);
        // 提取来源
        List<String> sources = extractSource(relevantDocs);

        return ChatResp.builder()
                .answer(answer)
                .sources(sources)
                .modified(false)
                .relevantDocsCount(relevantDocs.size())
                .build();
    }

    /**
     * 特殊规则处理
     * @param req 请求
     * @return 特殊规则响应
     */
    private String applySpecialRules(ChatReq req) {
        Map<String, String> context = req.getContext();

        // 若用户是小a 询问值班问题时返回特殊答案
        if (context != null && "小a".equals(context.get("identity"))) {
            String question = req.getQuestion().toLowerCase();
            if (question.contains("值班") || question.contains("谁") || question.contains("周")) {
                log.info("触发特殊规则：用户小a查询值班");
                return "根据值班表查询是  小b  值班";
            }
        }
        return null;
    }

    /**
     * 构建提示词
     * @param question 问题
     * @param relevantDocs 相关文档
     * @param context 上下文
     * @return 提示词
     */
    private String buildPrompt(String question, List<TextSegment> relevantDocs, Map<String, String> context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个专业的文档助手，请根据以下文档内容回答用户的问题。\n\n");

        // 添加文档内容
        if (!relevantDocs.isEmpty()) {
            prompt.append("=== 相关文档内容 ===\n");
            for (int i = 0; i < relevantDocs.size(); i++) {
                TextSegment seg = relevantDocs.get(i);
                prompt.append(String.format("\n 【文档片段 %d】 \n", i + 1));
                prompt.append("来源： ").append(seg.metadata("source")).append("\n");
                prompt.append("内容： ").append(seg.text()).append("\n");
            }
            prompt.append("\n===================\n\n");
        } else {
            prompt.append("注意：未找到相关文档，请基于你的知识回答。\n\n");
        }

        // 添加额外上下文
        if (context != null && !context.isEmpty()) {
            prompt.append("=== 额外信息 ===\n");
            context.forEach((k, v) ->
                    prompt.append(String.format("%s： %s\n", k, v)));
            prompt.append("\n");
        }

        // 添加问题
        prompt.append("用户问题：").append(question).append("\n\n");

        // 添加回答要求
        prompt.append("回答要求：\n");
        prompt.append("1. 请基于上述文档内容回答问题\n");
        prompt.append("2. 如果文档中没有相关信息，请明确告知用户\n");
        prompt.append("3. 回答要简洁明了，直接回答问题\n");
        prompt.append("4. 不要包含’根据文档‘等描述性前缀\n");

        return prompt.toString();
    }

    /**
     * 提取文档来源
     * @param relevantDocs 文档
     * @return 文档来源
     */
    private List<String> extractSource(List<TextSegment> relevantDocs) {
        return relevantDocs.stream()
                .map(doc -> doc.metadata("source"))
                .distinct()
                .collect(Collectors.toList());
    }

}
