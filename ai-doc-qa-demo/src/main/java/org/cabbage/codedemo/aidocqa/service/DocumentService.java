package org.cabbage.codedemo.aidocqa.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author xzcabbage
 * @since 2025/10/26
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${document.base-path}")
    private String basePath;

    @Value("${document.chunk-size}")
    private int chunkSize;

    @Value("${document.chunk-overlap}")
    private int chunkOverlap;

    @Value("${rag.max-results}")
    private int maxResults;

    @Value("${rag.min-score}")
    private double minScore;

    /**
     * 加载文档目录
     * @param directoryPath 目录路径
     */
    public void loadDocuments(String directoryPath) throws IOException {
        log.info("开始加载文档目录: {}", directoryPath);

        File directory;
        if (directoryPath.startsWith("classpath:")) {
            directory = loadFromClasspath(directoryPath.substring(10)); // 去掉 "classpath:"
        } else {
            directory = new File(directoryPath);
        }

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("目录不存在： " + directoryPath);
        }
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            log.warn("目录为空：{}", directoryPath);
            return;
        }

        int totalChunks = 0;
        for (File file : files) {
            if (file.isFile()) {
                int chunks = loadDocument(file);
                totalChunks += chunks;
            }
        }
        log.info("文档加载完成，总计 {} 个文件，{} 个文档块", files.length, totalChunks);
    }

    /**
     * 加载单个文件
     * @param file 文件
     * @return chunk
     */
    public int loadDocument(File file) {
        log.info("正在加载文档: {}", file.getName());

        // 解析文档
        Document document = parseDocument(file);

        // 分割文档
        List<TextSegment> segments = spiltDocument(document);

        log.info("文档 {} 分割为 {} 个块", file.getName(), segments.size());

        // 为每个片段添加元数据
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Metadata metadata = Metadata.from(Map.of(
                    "source", file.getName(),
                    "chunk_index", String.valueOf(i),
                    "total_size", String.valueOf(segments.size())
            ));

            // 创建带有元数据的新片段
            segments.set(i, TextSegment.from(segment.text(), metadata));
        }

        // 生成向量并存储
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        log.info("文档 {} 已成功加载", file.getName());
        return segments.size();
    }

    /**
     * 从classpath加载文件
     * @param path 路径
     * @return 资源文件
     * @throws IOException io异常
     */
    private File loadFromClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Classpath 资源不存在: " + path);
        }
        return resource.getFile();
    }

    /**
     * 解析文档
     * @param file 文档
     * @return 解析内容
     */
    private Document parseDocument(File file) {
        String fileName = file.getName().toLowerCase();
        DocumentParser parser;
        if (fileName.endsWith(".txt")) {
            parser = new TextDocumentParser();
        } else if (fileName.endsWith(".pdf")) {
            parser = new ApachePdfBoxDocumentParser();
        } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            parser = new ApachePoiDocumentParser();
        } else {
            throw new IllegalArgumentException("不支持的文件格式：" + fileName);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return parser.parse(fis);
        } catch (IOException e) {
            throw new RuntimeException("解析文件失败: " + fileName, e);
        }
    }

    /**
     * 分割文档
     * @param document 文档
     * @return 分割后文档
     */
    private List<TextSegment> spiltDocument(Document document) {
        DocumentSplitter splitter = DocumentSplitters.recursive(
                chunkSize,
                chunkOverlap
        );
        return splitter.split(document);
    }

    /**
     * 搜索相关文档
     * @param query 查询词
     * @return 相关文档块
     */
    public List<TextSegment> searchRelevantDocuments(String query) {
        // 生成查询向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // 搜索相关文档
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.debug("找到 {} 个相关文档块", matches.size());

        // 过滤掉 null 值并提取 TextSegment
        List<TextSegment> segments = matches.stream()
                .filter(match -> match != null && match.embedded() != null)  // 过滤 null
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());

        log.debug("过滤后有效文档块: {}", segments.size());

        return segments;
    }

    /**
     * 清空向量库
     */
    public void clearStore() {
        embeddingStore.removeAll();
        log.info("向量库已清空");
    }
}
