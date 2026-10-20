package com.tencentcloudapi.observability.springai.demo.boot.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.micrometer.observation.ObservationRegistry;

/**
 * RAG 使用的向量库配置。
 *
 * <p>使用 Spring AI 自带的 {@link SimpleVectorStore}（内存实现 + JSON 持久化）
 * 作为知识库存储；初次启动时读取 {@code classpath:/docs/*.md}，用
 * {@link TokenTextSplitter} 切片后调用混元 embedding 模型批量 embedding，
 * 结果写入进程所在目录的 {@code vector-store.json} 文件。后续启动若该文件
 * 已存在则直接加载，不再付费调用 embedding API。
 *
 * <p>每次 {@link SimpleVectorStore#add(List)} 都会触发
 * {@code EmbeddingModel.embed()} 调用，由本仓库 CLS SDK 自动采集为
 * {@code spring.ai.embedding} 的 observation（在 CLS 上呈现为
 * {@code gen_ai.operation.name=embedding} 的 step 子 span）。
 *
 * <p>运行时的 {@code RetrievalAugmentationAdvisor} 发起的 similarity search
 * 同样会产生 {@code vector_store.similarity_search} observation
 * （在 CLS 上呈现为 {@code gen_ai.operation.name=retrieval} 的 step 子 span）。
 */
@Configuration
@ConditionalOnProperty(prefix = "demo.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreConfig {

    private static final Logger LOG = LoggerFactory.getLogger(VectorStoreConfig.class);

    /** 分批 embedding 的批大小。tokenhub 实测 16 条一批可用。 */
    private static final int EMBEDDING_BATCH_SIZE = 16;

    /** 持久化文件路径（相对进程启动目录）。 */
    private static final String CACHE_FILE = "vector-store.json";

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel,
                                         ObservationRegistry observationRegistry) throws IOException {
        // 关键：把 ObservationRegistry 注入 builder — Spring AI 2.0 的
        // AbstractVectorStoreBuilder 默认用 ObservationRegistry.NOOP，不传就
        // 不会发射 db.vector.client.operation observation（即 retriever span
        // 不会出现在 CLS trace 树里）。
        SimpleVectorStore vs = SimpleVectorStore.builder(embeddingModel)
                .observationRegistry(observationRegistry)
                .build();

        Path cache = Paths.get(CACHE_FILE);
        if (Files.exists(cache)) {
            vs.load(cache.toFile());
            LOG.info("[rag] loaded persisted vector store from {}", cache.toAbsolutePath());
            return vs;
        }

        LOG.info("[rag] no persisted vector store at {} — ingesting docs now…", cache.toAbsolutePath());

        List<Document> allChunks = readAndSplitDocs();
        LOG.info("[rag] prepared {} chunks from classpath:/docs/*.md", allChunks.size());

        // 分批 embedding + add，避免单次请求过大
        for (int i = 0; i < allChunks.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, allChunks.size());
            List<Document> batch = allChunks.subList(i, end);
            vs.add(batch);
            LOG.info("[rag] embedded batch {}..{} ({} chunks)", i, end - 1, batch.size());
        }

        File cacheFile = cache.toFile();
        vs.save(cacheFile);
        LOG.info("[rag] persisted {} chunks to {}", allChunks.size(), cacheFile.getAbsolutePath());

        return vs;
    }

    private static List<Document> readAndSplitDocs() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:/docs/*.md");

        List<Document> rawDocs = new ArrayList<>();
        for (Resource r : resources) {
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String fileName = r.getFilename();
            Document doc = new Document(text);
            if (fileName != null) {
                doc.getMetadata().put("source", fileName);
            }
            rawDocs.add(doc);
        }

        // 用 TokenTextSplitter 把每篇文章切成多个 chunk，便于后续检索
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(rawDocs);
    }
}
