# ESDID · 电商销售数据智能分析平台

一个「能自己写 SQL 的分析助手」：用户在网页上问「上个月各品类销售额占比是多少」，系统理解问题 → 检索口径 → 生成 SQL → 自检 → 执行 → 出图 → 给出结论，全程流式返回。

- **后端**：Spring Boot 4 / Java 25，19 个分析接口、4.4 万条订单、预聚合 + 缓存 + 异步导入
- **前端**：Vue 3 + Element Plus + ECharts
- **Agent**：Python FastAPI + LangGraph，RAG 检索 + SQL 生成 + Critic 自检 + 流式 SSE

---

## 1. 它解决什么问题

「销售额是多少」这种问题，难点不在 SQL 语法，而在**口径**：哪个时间窗、哪个指标、按什么维度、空结果怎么解释。纯 LLM 直接写 SQL，会算错日期、编造字段、把「各品类占比」写成绝对值。

这套 Agent 的做法是：

1. **时间窗交给规则引擎**，不让 LLM 算日期（LLM 会把「上个月」算成当月）；
2. **检索四类知识**（SQL 模板 / 表结构卡 / 业务词典 / few-shot）拼进上下文，让模型「照着写」而不是「凭空写」；
3. **SQL 过了 AST 白名单才执行**（只读、三张表、函数白名单、强制 LIMIT）；
4. **Critic 自检**：执行前再让模型按检查清单复核一遍（时间窗、指标、JOIN、空结果）；
5. **结果集判分**：评测算「查出来的数对不对」，不算「SQL 长得像不像」。

---

## 2. 架构

```
Vue 3 (AssistantView)
   │  fetch + ReadableStream 解析 SSE
   ▼
FastAPI  /chat  ─────────────────────────────────────────────┐
   │                                                        │
   ▼                                                        │
LangGraph StateGraph                                        │
  understand ──► clarify?（问题有歧义就反问）                 │
     │                                                      │
     ▼                                                      │
  retrieve（dense + BM25 → RRF → rerank → MMR → 配额）       │
     │                                                      │
     ▼                                                      │
  generate（JSON 契约：sql/confidence/assumptions）          │
     │                                                      │
     ▼                                                      │
  critic（自检不过 → 回炉重写，最多 2 次）                     │
     │                                                      │
     ▼                                                      │
  execute（sqlglot 白名单 + 只读连接 + 超时）                 │
     │                                                      │
     ▼                                                      │
  analyze（结论 + ECharts 配置 JSON） ──► SSE answer/chart/done
```

知识库：Milvus 2.4（1024 维 BGE-M3，HNSW COSINE）+ 版本化集合 `agent_knowledge_v{n}` + alias 热切换，灌库不中断服务。

---

## 3. 三个可量化的点

### 3.1 检索：R@5 0.778 → 0.988

81 题检索评测（`agent/docs/rag-evaluation.md`）：

| 策略 | R@1 | R@5 | MRR | nDCG@5 | p50 |
|---|---|---|---|---|---|
| 旧：md5 2-gram 哈希 256 维 | .679 | .778 | .742 | .738 | <1ms |
| dense BGE-M3 | .889 | .963 | .924 | .932 | 14ms |
| BM25 | .790 | .926 | .845 | .864 | 16ms |
| hybrid RRF | .889 | .963 | .919 | .929 | 14ms |
| **hybrid + rerank** | .876 | **.988** | **.929** | **.944** | 376ms |

结论：换成真语义向量把 R@5 从 .778 抬到 .963，rerank 再补到 .988；语料只有 97 条时 BM25 混合对整体提升有限（dense 已近饱和），但 hard 子集上 rerank 把 R@5 从 .765 拉到 .941。

### 3.2 端到端：执行准确率 12.5% → 90.6%

79 题 golden set（64 可答 + 5 澄清 + 5 拒答 + 5 注入），DeepSeek，temperature=0：

| 配置 | valid_sql | exec_accuracy | 澄清率 | 注入拦截 | p50 | p95 |
|---|---|---|---|---|---|---|
| 无 RAG | 57.8% | 12.5% | 100% | 80% | 10.9s | 27.7s |
| **有 RAG** | **100%** | **90.6%** | **100%** | **100%** | 10.1s | 29.4s |

同一份代码三次跑分 92.2 / 89.1 / 90.6，报告里统一写「约 90%」并标注 ±1.5pt 抖动。

### 3.3 微调：1.5B LoRA 学会这套 schema

8G 显存、fp16 LoRA，216 steps / 17 分钟，可训练参数 1.18%；在 11 个**留出模板**的 77 题上：

| 配置 | 准确率 |
|---|---|
| base（Qwen2.5-Coder-1.5B） | 0% |
| base + RAG | 0% |
| **LoRA** | **35.1%** |
| LoRA + RAG | 20.8% |

有意思的是 RAG 对微调后的小模型是**负收益**（35.1% → 20.8%）：训练时没有检索上下文，推理时突然多 1200 字符，分布不一致。详情见 `agent/finetune/README.md`。

---

## 4. 快速开始

### 4.1 Docker（推荐，一键起 Milvus + Redis + PG + Agent）

```bash
cd agent
cp .env.example .env      # 填 LLM / embedding 的 key
docker compose up -d
# Agent: http://localhost:8000/docs
```

### 4.2 本地跑

```bash
cd agent
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements.txt
python -m app.rag.ingest          # 灌 97 条知识（幂等，alias 热切换）
uvicorn app.main:app --port 8000
```

前端 `frontend/` 里 `npm i && npm run dev`，vite 已把 `/agent` 代理到 8000。

数据依赖：MySQL `ecommerce` 库（orders / sales_target / daily_summary），连不上时 Agent 会降级（Milvus→本地内存、Redis→进程内、PG→关闭），主链路不崩。

---

## 5. 目录

```
backend/springboot-ES   Spring Boot 分析接口（19 个）
frontend/               Vue3 + ECharts
agent/
  app/
    graph/              LangGraph 状态机（state/nodes/build/prompts/schemas）
    rag/                检索（embedding / bm25 / fusion / rerank / retriever）
    rag/docs/           四类知识：52 模板 + 4 表卡 + 26 词典 + 15 few-shot
    eval/               检索评测 + 端到端评测（golden set / 判分 / 报告）
    memory/             Redis 短期会话 + PG 长期归档
  finetune/             数据合成 + LoRA 训练 + 四组对比
  docs/                 RAG 评测方法、端到端评测方法
```

---

## 6. 已知限制

- **PostgreSQL 默认不在线**：health 探测 2s 超时后降级，长期记忆功能需要自己起 PG；
- **embedding/rerank 依赖外部 API**（SiliconFlow），离线环境只能用本地降级；
- **golden set 只有 79 题**，且参考答案由模板生成，存在「参考答案本身有 bug」的风险（已修过一轮 DATE 取整问题）；
- **Java 后端与 Agent 尚未共享鉴权**：Agent 侧未做登录态校验，生产部署需要补网关；
- 微调模型只作为降级候选，没有上线。

---

## 7. 文档

- `agent/docs/rag-evaluation.md`：检索评测方法与完整消融
- `agent/docs/eval-methodology.md`：端到端判分口径、提升过程、失败聚类
- `agent/finetune/README.md`：微调数据管线、训练配置、四组对比
- `agent/README.md`：Agent 模块细节与设计决策