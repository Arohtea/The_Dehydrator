# The Dehydrator 系统图册

本文档依据当前仓库代码整理，覆盖 Web UI、Business Service、AI Service 以及 PostgreSQL、Redis、RabbitMQ、MinIO、Qdrant 的现状关系。

## 1. 阅读约定

- **ER 图**以 Spring Data JPA 实体为准。实体之间目前没有使用 `@ManyToOne`、`@OneToMany` 或 `@JoinColumn`，因此图中的大部分连线是业务逻辑上的关联，不代表 PostgreSQL 已创建物理外键。
- Spring Boot 的默认命名策略会把 Java 字段转换为下划线列名，例如 `documentId` 对应 `document_id`，`createdAt` 对应 `created_at`。
- 实体主键在 Java 中声明为 `String` 并使用 `GenerationType.UUID` 生成 UUID 格式字符串；代码没有显式声明 PostgreSQL `uuid` 类型，图中统一按 `varchar` 表示。
- `ai_doc_id` 是 AI Service/Qdrant 中的文档标识，与 PostgreSQL 的 `id` 不是同一个值。分析任务消息使用的是 `Document.ai_doc_id`。
- “控制图”在本文中指软件系统的控制流、状态控制、取消和超时控制，不是统计学意义上的质量控制图。

## 2. ER 图：PostgreSQL 业务实体

```mermaid
erDiagram
    DOCUMENTS {
        varchar id PK "UUID 格式字符串，业务文档 ID"
        varchar filename "原始文件名"
        varchar minio_path "MinIO 对象路径"
        bigint file_size "文件字节数"
        text ai_doc_id "Qdrant 文档 ID，异步回填"
        timestamp created_at "创建时间"
    }

    ANALYSIS_TASKS {
        varchar id PK "UUID 格式字符串，分析任务 ID"
        varchar document_id "逻辑关联 documents.id"
        varchar status "PENDING/PROCESSING/CANCELLING/COMPLETED/FAILED/CANCELLED"
        varchar mode "deep 或 quick"
        text reference_library_ids "JSON 数组，逻辑 N:M"
        text reference_library_names "JSON 数组快照"
        text argument_chain "JSON 文本"
        text logic_flaws "JSON 文本"
        text cross_validation "JSON 文本"
        integer progress "0-100"
        varchar current_step "当前处理步骤"
        timestamp created_at "创建时间"
        timestamp completed_at "完成时间"
    }

    REFERENCE_LIBRARIES {
        varchar id PK "UUID 格式字符串，资料库 ID"
        varchar name "资料库名称"
        varchar system_key UK "系统资料库标识，可为空"
        timestamp created_at "创建时间"
    }

    REFERENCE_FOLDERS {
        varchar id PK "UUID 格式字符串，文件夹 ID"
        varchar library_id "逻辑关联 reference_libraries.id"
        varchar name "文件夹名称"
        timestamp created_at "创建时间"
    }

    REFERENCE_CATEGORIES {
        varchar id PK "UUID 格式字符串，分类 ID"
        varchar library_id "逻辑关联 reference_libraries.id"
        varchar name "分类名称"
        timestamp created_at "创建时间"
    }

    REFERENCE_DOCUMENTS {
        varchar id PK "UUID 格式字符串，资料文档 ID"
        varchar library_id "逻辑关联 reference_libraries.id"
        varchar filename "原始文件名"
        varchar display_name "展示名称"
        varchar folder_id "逻辑关联 reference_folders.id，可为空"
        varchar category_id "逻辑关联 reference_categories.id，可为空"
        varchar source_document_id "逻辑关联 documents.id，分析文档镜像可为空"
        varchar minio_path "MinIO 对象路径"
        bigint file_size "文件字节数"
        text ai_doc_id "Qdrant 文档 ID，可异步回填"
        timestamp created_at "创建时间"
    }

    SYSTEM_SETTINGS {
        varchar id PK "固定为 default"
        text api_key "智谱 API Key"
        varchar tavily_api_key "Tavily API Key，最长 512 字符"
        varchar model "LLM 模型名"
        integer map_workers "并发线程数"
        integer chunk_size "分块字符数"
        integer chunk_overlap "分块重叠字符数"
    }

    DOCUMENTS ||--o{ ANALYSIS_TASKS : "document_id（逻辑）"
    DOCUMENTS ||--o{ REFERENCE_DOCUMENTS : "source_document_id（镜像逻辑）"
    REFERENCE_LIBRARIES ||--o{ REFERENCE_DOCUMENTS : "library_id（逻辑）"
    REFERENCE_LIBRARIES ||--o{ REFERENCE_FOLDERS : "library_id（逻辑）"
    REFERENCE_LIBRARIES ||--o{ REFERENCE_CATEGORIES : "library_id（逻辑）"
    REFERENCE_FOLDERS ||--o{ REFERENCE_DOCUMENTS : "folder_id（子字段可为空）"
    REFERENCE_CATEGORIES ||--o{ REFERENCE_DOCUMENTS : "category_id（子字段可为空）"
    REFERENCE_LIBRARIES }o--o{ ANALYSIS_TASKS : "reference_library_ids JSON（逻辑 N:M）"
```

### 2.1 关系和约束说明

| 关系 | 当前实现 | 删除/一致性规则 |
| --- | --- | --- |
| `documents` -> `analysis_tasks` | `analysis_tasks.document_id` 字符串字段，代码按文档查询任务 | 删除文档时通过业务服务先删除资源；任务表没有实体级级联声明 |
| `reference_libraries` -> `reference_documents` | `reference_documents.library_id` 字符串字段 | 资料库只能删除空库；系统资料库 `AUTO_ANALYSIS_ARCHIVE` 不允许删除 |
| `reference_libraries` -> `reference_folders/categories` | 同一资料库内名称唯一 | 删除文件夹/分类前检查是否仍被资料引用 |
| `reference_documents` -> `documents` | `source_document_id` 用于分析文档自动归档镜像 | 删除源文档会删除所有镜像；删除带源文档链接的镜像会回溯删除源文档 |
| `analysis_tasks` -> `reference_libraries` | `reference_library_ids`、`reference_library_names` 为 JSON 文本，不是中间表 | 创建任务时清洗、去重 ID，并保存名称快照；交叉验证时按 ID 检索 Qdrant |
| `documents/reference_documents` -> MinIO | `minio_path` 保存对象键 | 删除时删除对应对象；镜像复用源文件路径，不重复上传对象 |
| `documents/reference_documents` -> Qdrant | `ai_doc_id` 异步回填 | 删除时调用 AI Service 删除同一 `doc_id` 的向量点 |

### 2.2 跨存储的向量实体

Qdrant 没有对应的 JPA 表，`dehydrator_docs` collection 中每个 point 的 payload 由 AI Service 写入：

```text
point_id    : UUID，Qdrant 点 ID
vector      : 设置页指定的 OpenAI 兼容向量模型输出
text        : 分块文本
doc_id      : AI 文档 ID，对应 documents.ai_doc_id 或 reference_documents.ai_doc_id
source_type : analysis_document 或 reference_document
library_id  : 参考资料所属资料库 ID，分析文档通常为空
```

分析文档归档为参考资料时，AI Service 会复制原分析文档的向量点，生成新的 `doc_id`，并将 `source_type` 改为 `reference_document`、写入 `library_id`；Business Service 再把新 ID 回填到镜像记录。

## 3. 流程图：文档上传到分析结果

```mermaid
flowchart TD
    U[用户在 Web UI 选择 PDF / DOCX / TXT] --> W1[POST /api/documents/upload]
    W1 --> B1[DocumentController]
    B1 --> B2{文件是否可读且上传成功?}
    B2 -- 否 --> E1[返回错误]
    B2 -- 是 --> M1[MinIO 写入原始对象]
    M1 --> P1[PostgreSQL 保存 documents]
    P1 --> A0[创建分析资料库镜像 reference_documents]
    A0 --> R1[异步调用 AI Service /api/document/upload]
    R1 --> A1[解析 PDF / DOCX / TXT]
    A1 --> A2[按 chunk_size / chunk_overlap 切分]
    A2 --> A3[调用设置页指定的向量模型生成向量]
    A3 --> Q1[写入 Qdrant dehydrator_docs]
    Q1 --> P2[回写 documents.ai_doc_id]
    P2 --> AR1[克隆分析向量为参考向量]
    AR1 --> AR2[LLM 自动推荐文件夹和分类]
    AR2 --> P3[回写镜像 ai_doc_id / folder_id / category_id]

    U2[用户点击开始分析] --> W2[POST /api/analysis/start]
    W2 --> B3[校验 documentId、文档存在、ai_doc_id 已就绪]
    B3 -- 未就绪 --> E2[400 或 202：稍后再试]
    B3 -- 通过 --> P4[创建 analysis_tasks，状态 PENDING]
    P4 --> MQ1[RabbitMQ analysis.request]
    MQ1 --> P5[提交后派发器更新为 PROCESSING]
    MQ1 --> C1[AI Consumer 接收任务]
    C1 --> Q2[Qdrant scroll 获取分析文档分块]
    Q2 --> AC1[论据链 MAP：并发调用 LLM]
    AC1 --> AC2[论据链 REDUCE：汇总调用 LLM]
    AC2 --> PAR[并行分支]
    PAR --> LF[逻辑漏洞检测]
    PAR --> CV[交叉验证：逐论点并发]
    CV --> REF[按资料库 ID 检索参考向量]
    CV --> MODE{分析模式}
    MODE -- quick --> NO_WEB[跳过联网验证]
    MODE -- deep --> WEB[Tavily Search API 联网验证]
    REF --> CV2[组合证据调用 LLM]
    NO_WEB --> CV2
    WEB --> CV2
    LF --> RES[组装分析结果]
    CV2 --> RES
    RES --> MQ2[RabbitMQ analysis.result]
    MQ2 --> P6[AnalysisResultListener 写入任务 JSON 结果]
    P6 --> DONE[COMPLETED，progress=100]

    C1 -. 分步进度 .-> MQ3[RabbitMQ analysis.progress]
    MQ3 -.-> P7[更新 progress / current_step]
    C1 -. LLM token .-> REDIS1[Redis Stream analysis:stream:taskId]
    REDIS1 -.-> SSE[Business Service SSE /api/analysis/stream/taskId]
    SSE -.-> UI[Web UI 实时展示]

    U3[用户点击取消] --> W3[POST /api/analysis/task/taskId/cancel]
    W3 --> REDIS2[写入 analysis:cancel:taskId，TTL 30 分钟]
    W3 --> CANCEL[任务标记 CANCELLING]
    REDIS2 -.定期检查.-> C1
```

### 3.1 主链路的关键分支

1. 文档上传返回的是 PostgreSQL 文档记录；向量化在后台异步执行，`ai_doc_id` 为空期间不能开始分析，异步回写受文档行锁和删除状态保护。
2. RabbitMQ 发送失败时，任务直接转为 `FAILED`，当前步骤为“任务提交失败”。
3. AI Service 的分析消费只读取 `source_type=analysis_document` 的向量，参考资料只在交叉验证阶段通过 `source_type=reference_document` 和 `library_id` 检索。
4. 快速模式不执行联网搜索；深度模式要求数据库已配置 Tavily API Key，并为每个论点调用 Tavily Search API。
5. Token 流和任务结果是两条不同通道：Token 走 Redis Stream + SSE（支持回放和 Last-Event-ID 续传），最终 JSON 结果走 RabbitMQ + PostgreSQL；任务终态会写回同一 Stream 收口 SSE。

## 4. 流程图：资料库与自动归档

```mermaid
flowchart LR
    L[创建资料库] --> L1[reference_libraries]
    L1 --> F[创建/重命名文件夹]
    L1 --> C[创建/重命名分类]
    L1 --> D[上传参考资料]
    D --> D1[写入 MinIO reference/libraryId/...]
    D1 --> D2[保存 reference_documents]
    D2 --> D3[异步 AI 解析、切块、向量化]
    D3 --> D4[Qdrant source_type=reference_document]
    D4 --> D5[回写 reference_documents.ai_doc_id]
    D5 --> EDIT[编辑 display_name / folder_id / category_id]

    DOC[分析文档上传] --> MIRROR[自动创建分析资料库镜像]
    MIRROR --> DEFAULT[默认“待整理”文件夹 + “未分类”分类]
    DEFAULT --> VEC[分析向量化完成]
    VEC --> CLONE[AI Service 克隆 analysis_document 向量]
    CLONE --> CLASSIFY[LLM 根据候选项推荐归档位置]
    CLASSIFY --> CONF{confidence >= 0.6?}
    CONF -- 是 --> AUTO[创建/复用推荐文件夹与分类并回写]
    CONF -- 否 --> KEEP[保留默认归档位置]

    DEL[删除入口] --> TYPE{删除对象}
    TYPE -- 分析文档 --> CASCADE[删除源文档、关联镜像、MinIO 对象和向量]
    TYPE -- 带源链接的参考镜像 --> CASCADE
    TYPE -- 独立参考文档 --> SINGLE[删除参考记录、MinIO 对象和向量]
    TYPE -- 资料库 --> EMPTY{资料库是否为空且非系统库?}
    EMPTY -- 否 --> BLOCK[409，拒绝删除]
    EMPTY -- 是 --> LIBDEL[删除文件夹、分类和资料库]
```

## 5. 控制图：分析任务状态机

```mermaid
stateDiagram-v2
    [*] --> PENDING : createTask 保存任务
    PENDING --> PROCESSING : 事务提交后派发器发送请求
    PENDING --> FAILED : RabbitMQ 发送异常

    PROCESSING --> PROCESSING : progress 消息更新 progress/current_step
    PROCESSING --> COMPLETED : analysis.result 成功
    PROCESSING --> FAILED : analysis.result.failed=true
    PENDING --> CANCELLING : 取消 API 写 Redis 取消键
    PROCESSING --> CANCELLING : 取消 API 写 Redis 取消键
    CANCELLING --> CANCELLED : AI Service 发布取消确认
    PROCESSING --> CANCELLING : 定时清理：创建超过 30 分钟

    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]

    note right of PROCESSING
      AI Service 每隔约 10 个 token 检查一次
      analysis:cancel:{taskId}。
      检测到取消后抛出 AnalysisCancelled，
      停止当前 LLM 流。
    end note

    note right of COMPLETED
      终态守卫：终止中的任务不接受迟到的成功结果；
      只有 AI Service 取消确认才能进入 CANCELLED。
    end note
```

### 5.1 状态控制规则

| 控制点 | 实现位置 | 行为 |
| --- | --- | --- |
| 开始前置条件 | `AnalysisController.start` | `documentId` 必填，文档存在且 `ai_doc_id` 已回填；否则返回 400/202 |
| 状态进入处理中 | `AnalysisService.createTask` / `AnalysisTaskDispatcher` | 事务先保存 `PENDING`，提交后投递消息并更新 `PROCESSING` |
| 取消 | `AnalysisService.cancelTask` | `PENDING/PROCESSING` 进入 `CANCELLING`；Redis 取消键保留 30 分钟；AI 确认后才为 `CANCELLED` |
| 取消感知 | `stream_publisher.py` | 首 token 前、每 10 个 token、流结束后检查取消键 |
| 进度更新 | `AnalysisResultListener.onProgress` | 仅 `PROCESSING` 任务接受进度消息 |
| 结果幂等/迟到保护 | `AnalysisResultListener.onResult` | `COMPLETED`、`FAILED`、`CANCELLED` 任务跳过后续结果 |
| 超时 | `AnalysisService.cleanupTimedOutTasks` | 每 5 分钟扫描 `PENDING/PROCESSING` 且创建超过 30 分钟的任务并进入 `CANCELLING` |
| 并行度 | AI `argument_chain.py` / `cross_validation.py` | 使用线程池；`map_workers` 控制论据 MAP 与交叉验证的并发上限 |
| 消费背压 | AI `mq_consumer.py` | RabbitMQ consumer `prefetch_count=1`，单次只处理一个分析任务 |

## 6. 控制图：服务、消息和缓存控制通道

```mermaid
flowchart LR
    UI[Vue Web UI]
    BS[Business Service<br/>Spring Boot]
    AI[AI Service<br/>FastAPI Consumer]
    PG[(PostgreSQL)]
    MQ{{RabbitMQ<br/>analysis.exchange}}
    R[(Redis)]
    S3[(MinIO)]
    V[(Qdrant)]
    LLM[OpenAI 兼容文本模型 / 向量模型]
    WEB[Tavily Search API]

    UI -- HTTP REST --> BS
    UI -- 任务轮询 --> BS
    BS -- SSE 订阅结果流 --> UI
    BS -- JPA 读写 --> PG
    BS -- 上传/删除对象 --> S3
    BS -- analysis.request --> MQ
    MQ -- analysis.request 消费 --> AI
    AI -- analysis.progress --> MQ
    AI -- analysis.result --> MQ
    MQ -- progress/result Listener --> BS
    AI -- token XADD --> R
    BS -- Redis Stream 回放/阻塞读取 --> R
    BS -- 写 cancel key --> R
    AI -- 检查 cancel key --> R
    AI -- 向量写入/检索/删除 --> V
    AI -- LLM/Embedding 调用 --> LLM
    AI -- deep 模式联网核验 --> WEB
    BS -- 文档 REST 调用 --> AI
```

### 6.1 通道语义

| 通道 | 消息/键 | 生产者 -> 消费者 | 用途 |
| --- | --- | --- | --- |
| RabbitMQ 请求 | `analysis.request` | Business -> AI | 投递任务 ID、AI 文档 ID、模式、资料库 ID、模型配置及深度分析所需的 Tavily Key |
| RabbitMQ 进度 | `analysis.progress` | AI -> Business | 更新任务百分比和当前步骤 |
| RabbitMQ 结果 | `analysis.result` | AI -> Business | 保存三类分析 JSON，并将任务置为完成或失败 |
| Redis Stream | `analysis:stream:{taskId}` | AI/Business -> SSE -> UI | 保存批量 token、步骤、进度和终态事件，支持回放、续传、长度限制和 TTL |
| Redis 取消键 | `analysis:cancel:{taskId}` | Business -> AI | 协作式取消信号，TTL 30 分钟 |

## 7. 现状边界与后续数据库演进建议

- 当前 ER 里的逻辑外键没有数据库级约束，删除和引用校验依赖 `ReferenceArchiveService`、`ReferenceLibraryService` 等业务代码；若后续需要更强一致性，应评估补充 FK、索引和事务边界。
- `analysis_tasks.reference_library_ids` / `reference_library_names` 是 JSON 快照，优点是保留任务当时的资料库名称，缺点是无法直接做关系查询和级联校验。若需要审计或按资料库统计，建议新增 `analysis_task_reference_libraries(task_id, library_id, library_name_snapshot)` 中间表，并保留名称快照字段。
- `argument_chain`、`logic_flaws`、`cross_validation` 当前作为 TEXT 保存 JSON；如果需要按论点、漏洞类型或验证结论检索，建议拆出结果明细表或使用 PostgreSQL `jsonb` 加索引。
- 任务的“取消”是协作式的：业务服务先写 `CANCELLING` 和 Redis 信号，AI Service 在流式调用中检查并通过 RabbitMQ 回传确认，业务服务再进入 `CANCELLED`；取消请求与已经完成的结果并发到达时由任务行锁和终态守卫收口。
- Qdrant 与 PostgreSQL 没有跨存储事务。代码对删除和异步向量化采用补偿式清理；生产环境应增加失败重试、孤儿向量扫描和向量化失败状态字段。

## 8. 代码依据

- 数据实体：`Business_Service/src/main/java/com/arohtea/business_service/model/`
- 文档、分析与资料库服务：`Business_Service/src/main/java/com/arohtea/business_service/service/`
- RabbitMQ、Redis 通道：`Business_Service/src/main/java/com/arohtea/business_service/config/`、`AI_Service/services/mq_consumer.py`、`AI_Service/services/stream_publisher.py`
- AI 文档处理与分析：`AI_Service/api/routes/`、`AI_Service/services/`
- 前端 API 与任务页面：`Web_ui/src/api/index.js`、`Web_ui/src/views/AnalysisResult.vue`
