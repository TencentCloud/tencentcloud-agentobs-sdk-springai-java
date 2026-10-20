# Spring AI 1.0 核心概念

Spring AI 是 Spring 生态为构建生成式 AI 应用提供的统一抽象层，于 2025 年发布 1.0 GA 版本。

## 四大核心抽象

### 1. ChatClient

高层级流式 API，开发者只需 `chatClient.prompt().user("...").call().content()` 即可完成一次对话。

```java
String answer = ChatClient.builder(chatModel)
        .defaultSystem("你是一个贴心的助理")
        .defaultTools(weatherTool, timeTool)
        .defaultAdvisors(ragAdvisor)
        .build()
        .prompt().user(question).call().content();
```

### 2. ChatModel

底层模型抽象，不同厂商（OpenAI、Anthropic、腾讯混元、阿里百炼、本地 Ollama）都实现这个接口。只要换一个 `*ChatModel` Bean，整个应用无需改代码即可切换 LLM。

### 3. Advisor

拦截器模式，在 ChatClient 调用的前后插入自定义逻辑。开箱即用的 Advisor：

- `MessageChatMemoryAdvisor` — 对话历史
- `RetrievalAugmentationAdvisor` — RAG 检索增强
- `SafeGuardAdvisor` — 内容安全检查
- `SimpleLoggerAdvisor` — 调试日志

### 4. ToolCallback / @Tool

把普通 Java 方法暴露为 LLM 可调用的 function。只需 `@Tool` 注解加上描述即可：

```java
@Tool(description = "查询指定城市的实时天气")
public String getWeather(@ToolParam String city) { ... }
```

## 可观测性

Spring AI 内置 Micrometer `Observation` 发射点，每一次 ChatClient / ChatModel / Advisor / ToolCallback / EmbeddingModel / VectorStore 调用都会产生 Observation 事件。只需向 `ObservationRegistry` 注册 Handler 即可消费这些事件 — 这正是本仓库 SDK 的接入点。
