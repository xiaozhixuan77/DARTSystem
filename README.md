# DART — 双 Agent 协作的编程技术资源智能检索与知识整理系统

基于 Spring Boot 3.4.5 + Vue 3 的智能检索系统：**Search Agent**（联网搜索与质量评分）与 **Curator Agent**（结构化报告生成）双 Agent 协作，融合 RAG 知识库优先检索、报告自动沉淀与知识图谱闭环，全程 SSE 流式输出。

## 功能特性

| 模块 | 功能 |
|------|------|
| 智能检索 | 前置 LLM 单次解析（领域判断 / 意图分类 / 关键词优化 / 2 个变体检索词），Bing 主词+变体词并行搜索，虚拟线程并行抓取 |
| 质量评分 | 时效 / 权威 / 深度 / 匹配四维加权评分，无关页过滤，按意图取 Top 5–12 |
| 报告生成 | Curator Agent 三节结构报告（核心概念 / 操作要点 / 实践延伸），Jaccard 去重、token 级流式、截断检测与降级兜底 |
| RAG 优先 | 历史沉淀知识向量检索（双向量取最大余弦，≥0.50 命中直接生成），命中条目 1-hop 关联知识拼入上下文 |
| 知识沉淀 | 生成成功的报告自动写入知识库（分块 + 向量化 + MySQL/Redis 双写），按意图自动打标 |
| 知识图谱 | 沉淀时 LLM 抽取实体与三元组（topic / concept），跨条目关联，ECharts 力导向图全局展示 + 节点下钻 |
| 会话记忆 | 滚动摘要增量压缩，模糊输入解析 + followUp 续答（记忆三出口注入） |
| 流式体验 | SSE 六类事件；会话级广播，刷新页面续流重放，多订阅者互不影响 |
| 报告缓存 | 查询规范化哈希键，24 小时 TTL，命中直接推送 |
| 链路追踪 | 每次查询记录出口分支（rag / follow_up / search / error）与降级原因 |

## 系统架构

### 双 Agent 分工

- **Search Agent** — 主词 + 2 变体词并行搜索合并（变体词经 embedding 主题校验防漂移），Jsoup 虚拟线程并行抓取，T/A/D/M 四维评分 + 无关页过滤，按意图筛选 Top-N。
- **Curator Agent** — Jaccard 去重后三节流式生成：节 1 token 级直推（打字机效果）、节 2 并行缓冲流式（失败完整重试 + 分片回放）、节 3 流式直推；截断检测与代码块平衡兜底，失败自动降级。

### 核心链路

```
用户输入
  → ① 报告缓存检查（规范化查询哈希，24h 内命中直接推送）
  → ② 前置 LLM 单次解析（领域 / 意图 / 关键词 / 变体词 / 续答判定）
  → ③ 续答路由（followUp 意图 → 基于会话记忆回答，跳过检索）
  → ④ RAG 向量检索 ∥ Search Agent 并行启动（RAG 命中 ≥0.50 即短路）
  → ⑤ Curator Agent 三节流式生成报告（风格按意图注入）
  → ⑥ 追问生成 → 写入缓存 → 自动沉淀知识库 → 分块向量化双写
全程 SSE 流式推送（status / search_result / curated_report / content / done / error）
```

## 技术栈

### 后端 — Spring Boot 3.4.5（Java 21）

- Spring AI 1.0：Chat 走 DeepSeek 官网，Embedding 走硅基流动 BAAI/bge-m3（1024 维）
- MyBatis-Plus + MySQL 8.0（11 张表，逻辑删除）+ Redis Stack（HNSW 向量索引）
- 自研双 Agent 编排：虚拟线程并行抓取（Jsoup）、三节流式生成、报告缓存、定时一致性补偿
- SSE 会话级广播（多订阅者、断线重连事件重放）、search_trace 链路追踪
- Knife4j API 文档（`/doc.html`）

### 前端 — Vue 3.5 + Vite 6

- TypeScript + Pinia + Element Plus + Vue Router
- markdown-it + highlight.js + Mermaid 渲染 + DOMPurify 净化
- ECharts 力导向知识图谱（全局视图 + 节点下钻）
- 测试：Vitest 单元测试 + Playwright E2E

## 项目结构

```
System/
├── dart-server/                 # Spring Boot 后端
│   └── src/main/java/com/dart/
│       ├── agent/               # curator（报告生成）/ search（检索评分）/ scoring（四维评分）/ splitter（分块）
│       ├── controller/          # REST + SSE 端点
│       ├── service/             # 核心编排与业务服务
│       ├── entity/ mapper/      # MyBatis-Plus 实体与 Mapper
│       └── broadcast/ config/ dto/ exception/ util/
├── dart-web/                    # Vue 3 前端
│   └── src/
│       ├── views/               # ChatView / KnowledgeView
│       ├── stores/              # Pinia（chat / knowledge）
│       ├── components/          # chat / knowledge / sidebar / common
│       └── api/ composables/ utils/ types/
├── docker-compose.yml           # MySQL 8.0 + Redis Stack
├── init.sql                     # 建表脚本（11 张表）
└── .env.example                 # 环境变量模板
```

## 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21 |
| Maven | 3.9+ |
| Node.js | 20 LTS |
| Docker | 含 Compose |

### 1. 启动基础设施

```bash
docker compose up -d     # MySQL(3306) + Redis Stack(6379)，首次启动自动执行 init.sql 建表
```

### 2. 配置环境变量

```bash
cp .env.example .env     # 填入两个 API Key
```

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | Chat 模型，[platform.deepseek.com](https://platform.deepseek.com) 注册 |
| `SILICONFLOW_API_KEY` | Embedding 模型（BAAI/bge-m3），[cloud.siliconflow.cn](https://cloud.siliconflow.cn) 注册 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_DATABASE` | 数据库配置（与 docker-compose.yml 对应） |

### 3. 启动后端

```bash
cd dart-server
mvn spring-boot:run     # 端口 8081，API 文档 http://localhost:8081/doc.html
```

### 4. 启动前端

```bash
cd dart-web
npm install
npm run dev             # http://localhost:5173（/api 代理到 8081）
```

## 测试

```bash
# 后端：单元测试 + JaCoCo 覆盖率报告（target/site/jacoco/）
cd dart-server && mvn verify

# 后端：集成测试（Testcontainers，需 Docker）
cd dart-server && mvn verify -Dgroups="integration"

# 前端：Vitest 单元测试
cd dart-web && npm test

# 前端：Playwright E2E（Playwright 自动启动前端，需后端与基础设施已运行）
cd dart-web && npm run test:e2e
```

外部 API（LLM / Bing / 页面抓取）在测试中全部 Mock。

## 提交规范

`<type>(<scope>): <subject>`（type：test / feat / refactor / fix / docs / chore / style），严格 TDD 原子提交（先 `test:` 红用例，后 `feat:` 实现）。

## 说明

本科毕业设计项目，仅供学习交流使用。
