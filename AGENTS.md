# 智能体项目规则（AGENTS.md）

本文件是仓库内所有编码智能体的共享规则与项目事实唯一来源。工具专属规则应在对应入口文件中补充，并引用本文件，避免重复维护。

## 项目概览

Ragent 是一个企业级智能体式 RAG 平台（Java 17 + Spring Boot 3.5.7 多模块 Maven 工程 + React 18 前端）。**不使用 Spring AI / LangChain4j**，大语言模型、嵌入模型和重排序模型的客户端层均为自研（`infra-ai` 模块）。仓库工作语言为中文：注释、文档、提交信息均使用中文，提交信息采用约定式提交规范（Conventional Commits，如 `fix(feedback): 修复取消反馈问题`）。

## 常用命令

### 后端（Maven，根目录执行）

```bash
./mvnw clean package -DskipTests        # 全量构建
./mvnw spring-boot:run -pl bootstrap    # 启动主应用（端口 9090，上下文路径 /api/ragent）
./mvnw spring-boot:run -pl mcp-server   # 启动独立 MCP 服务端（端口 9099）

./mvnw test -pl bootstrap                                    # 运行 bootstrap 模块全部测试
./mvnw test -pl bootstrap -Dtest=DeduplicationPostProcessorTest          # 运行单个测试类
./mvnw test -pl bootstrap -Dtest=ChunkPackerTest#testMethodName          # 运行单个测试方法

./mvnw spotless:apply                   # 手动应用许可证头格式化
```

注意：
- Spotless 在 `compile` 阶段自动给所有 Java 文件加 Apache 许可证头（模板在 `resources/format/copyright.txt`）。新建 Java 文件不必手写头部，编译时会自动补上。
- 约半数测试是 `@SpringBootTest` 集成测试，依赖本地基础设施（PostgreSQL、Redis、Milvus 等）和模型 API 密钥才能跑通；纯单元测试（如 `ChunkPackerTest`、`DeduplicationPostProcessorTest`）可直接运行。
- Maven Surefire 配置了 Mockito Java 代理；根 `pom.xml` 中空的 `<argLine/>` 属性是刻意保留的，勿删。

### 前端（`frontend/` 目录）

```bash
npm run dev       # Vite 开发服务器（端口 5173，/api 代理到 localhost:9090）
npm run build     # 生产构建
npm run lint      # ESLint（--max-warnings 0）
npm run format    # Prettier
```

### 本地基础设施

依赖：PostgreSQL（含 pgvector，5432）、Redis（6379）、Milvus（19530）、RocketMQ（9876）、S3 兼容存储（rustfs/minio，9000）。Docker Compose 文件在 `resources/docker/`，数据库模式与初始数据在 `resources/database/`（`schema_pg.sql`、`init_data_pg.sql`，版本升级脚本 `upgrade_*.sql`）。

模型供应商 API 密钥通过环境变量注入：`BAILIAN_API_KEY`、`AIHUBMIX_API_KEY`、`SILICONFLOW_API_KEY`（见 `bootstrap/src/main/resources/application.yaml` 的 `ai.providers` 配置段）。

## 架构

详细架构文档见 `docs/ragent-architecture.md`（含完整链路图与数据结构），改动 RAG 核心逻辑前建议先读。

### Maven 模块分层

- **framework** — 与业务无关的通用基础设施：三级异常体系与统一拦截、幂等、Snowflake ID、用户与链路追踪上下文跨线程透传（TTL）、SSE 封装、统一响应体。不依赖其他模块。
- **infra-ai** — AI 基础设施层，屏蔽模型供应商差异。`LLMService` / `EmbeddingService` / `RerankService` 三个门面服务 → `Routing*Service` 路由服务 → 各供应商客户端（SiliconFlow / Ollama / 百炼 / AIHubMix）。路由由 `ModelRoutingExecutor` + `ModelSelector` + `ModelHealthStore`（三态熔断器 CLOSED→OPEN→HALF_OPEN）实现健康感知与优先级降级。依赖 `framework`。
- **bootstrap** — 主应用，包含全部业务逻辑（约 400 个源文件）。依赖 `framework` 和 `infra-ai`。
- **mcp-server** — 独立 MCP 服务端应用，通过 MCP 协议向 `bootstrap` 暴露业务工具（销售数据、工单、天气等）。

### `bootstrap` 核心包（`com.nageoffer.ai.ragent`）

- `core/` — 文档解析（Tika/MinerU）、分块
- `knowledge/` — 知识库管理（CRUD、调度、MQ 消费）
- `ingestion/` — 文档入库流水线：基于节点编排的有向无环图引擎，包含获取、解析、分块、增强、丰富和索引六类节点（Fetcher/Parser/Chunker/Enhancer/Enricher/Indexer）；节点配置存入数据库，支持条件执行与链式输出
- `rag/` — RAG 核心：意图（`core/intent`）、查询改写（`core/rewrite`）、检索（`core/retrieve`）、歧义引导（`core/guidance`）、提示词组装（`core/prompt`）、MCP 集成（`core/mcp`）、对话记忆（`core/memory`）、向量存储（`core/vector`）、链路追踪（`trace`）
- `admin/`、`user/` — 管理后台与用户认证（Sa-Token）

### 对话核心链路（`StreamChatPipeline`）

入口 `RAGChatServiceImpl.streamChat()`（前置 `ChatQueueLimiter` 分布式排队限流）→ `StreamChatPipeline.execute()` 八阶段线性管道，三个短路分支：

1. 加载对话记忆（历史 + 摘要，超过阈值自动 LLM 摘要压缩）
2. 查询改写与子问题拆分（`QueryRewriteService`，LLM 失败时按标点规则兜底）
3. 意图识别（`IntentResolver` → `DefaultIntentClassifier`：树形意图 + 大语言模型打分，评分低于 0.35 时丢弃，全局最多保留 3 个意图）
4. **[短路]** 歧义引导：排名前两位的 KB 意图分数接近时，返回引导语让用户选择
5. **[短路]** 纯 `SYSTEM` 意图直接回复，不检索
6. 检索（`RetrievalEngine`：KB 意图走多通道检索，MCP 意图走 LLM 参数提取 → MCP 工具调用）
7. **[短路]** 检索为空时返回“未检索到相关文档”
8. 提示词组装（场景为 `KB_ONLY` / `MCP_ONLY` / `MIXED`，意图节点自定义模板优先于默认模板）→ SSE 流式输出

### 意图体系

三级意图树（`DOMAIN` → `CATEGORY` → `TOPIC`）存于 PostgreSQL 的 `t_intent_node` 表和 Redis 缓存（键为 `ragent:intent:tree`），只有叶子节点参与分类。意图类型 `IntentKind`：`KB`（关联 Milvus 集合）、`MCP`（关联 `toolId`）、`SYSTEM`（直接对话）。叶子节点可覆盖 `topK`、提示词模板、MCP 参数提取提示词等配置。

### 多通道检索

`MultiChannelRetrievalEngine`：所有启用的 `SearchChannel` 并行执行，当前四个通道按优先级依次为：意图定向检索（priority=1，存在 KB 意图时执行）、ES 关键词检索（priority=5，需 `rag.keyword.type=es`）、向量全局检索（priority=10，意图置信度低时触发）、You.com 联网检索（priority=20，需显式开启并配置 API Key）。随后 `SearchResultPostProcessor` 链按排序值串行精炼（去重 order=1 → RRF 融合 order=5 → 重排序 order=10 → 元数据富化 order=20）。阈值配置在 `application.yaml` 的 `rag.search` 配置段。

### 扩展点约定

新增能力的标准方式是实现接口并注册为 Spring Bean，由系统自动发现，**不需要修改框架代码**：

| 扩展点 | 接口 |
|---|---|
| 检索通道 | `SearchChannel` |
| 检索后处理器 | `SearchResultPostProcessor` |
| MCP 工具 | `MCPToolExecutor`（由 `DefaultMcpToolRegistry` 自动发现） |
| 入库节点 | `IngestionNode` |
| 模型供应商 | 在 `infra-ai` 层实现 `ChatClient` 等接口并配置候选列表 |
| 向量存储 | `VectorStoreService`（现有 Milvus / PgVector 两种实现） |

### 提示词模板

全部位于 `bootstrap/src/main/resources/prompt/*.st`，使用 `{key}` 占位符；`context-format.st` 采用多区段结构，由 `PromptTemplateLoader.renderSection()` 按区段渲染。修改回答行为时，通常应修改模板而非代码。

### 并发与上下文透传

8 个按负载特征独立配置的专用线程池（意图分类、多路检索、MCP 批量调用、RAG 上下文组装、记忆摘要、流式输出等），全部使用 `TtlExecutors` 包装，以透传用户与链路追踪上下文。新增异步逻辑时必须沿用此模式，否则会丢失上下文。可观测性通过 `@RagTraceNode` AOP 注解实现全链路追踪。
