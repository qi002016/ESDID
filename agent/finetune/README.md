# 微调实验：Qwen2.5-Coder-1.5B → 电商 SQL

这个目录回答一个问题：**在 8G 显存的笔记本上，能不能让一个小模型学会写这套库的 SQL？**

结论：能，但只到「会写这套 schema 的 SQL」的程度，离线上 DeepSeek 的水平还很远——所以它的定位不是替换线上模型，而是**一个可复现的微调实验 + 一组消融数字**。

---

## 1. 为什么是 1.5B + LoRA

| 约束 | 选择 |
|---|---|
| RTX 4060 Laptop 8G | 7B 全参/QLoRA 都不现实（Windows 上 bitsandbytes 4bit 也不稳），1.5B fp16 LoRA 峰值 7.8G，刚好 |
| 目标是「学 schema 与口径」而不是「学语言」 | 1.5B 足够记住 3 张表的列和 50 个模板的写法 |
| 要有对比数字 | 小模型才能在同一台机器上反复跑 base / LoRA / ±RAG 四组 |

---

## 2. 数据怎么来（防泄漏是重点）

`synth.py` 的管线：

```
52 个 SQL 模板
  → 每个模板让 LLM 生成 8 条不同口语问法（保留时间窗/品类槽位）
  → 参数实例化（换月份、换品类、换「最近 N 天/周」）
  → 执行过滤：过 sqlglot 白名单 + 结果非空，才留下
  → 按 template_id 切分：每 5 个模板留 1 个做 holdout（11 个）
```

产出：`data/train.jsonl` 573 条、`data/val.jsonl` 154 条、`data/holdout_templates.json` 11 个模板。

**为什么按模板切分而不是按样本随机切**：同一模板的不同问法 SQL 骨架几乎一样，随机切分会让「见过的模板」泄漏到测试集，指标虚高。留出整模板才是真正的泛化测试。

---

## 3. 训练配置

| 项 | 值 |
|---|---|
| 基座 | Qwen2.5-Coder-1.5B-Instruct |
| 方法 | LoRA（r=16, alpha=32, dropout=0.05） |
| target_modules | q/k/v/o/gate/up/down |
| 精度 | fp16 + gradient_checkpointing（没用 4bit） |
| batch | 1 × grad_accum 8，max_len 1536 |
| lr / epoch | 2e-4 / 3 |
| 可训练参数 | 18,464,768 / 1,562,179,072（1.18%） |
| 实耗 | 216 steps，16m48s，train_loss 0.066，eval_loss 0.299，GPU 峰值 7.8G |

产物：`out/lora-sql/`。踩坑记录：transformers 5 的 `TrainingArguments` 已经没有 `warmup_ratio`，要自己按 `total_steps * 0.05` 算 `warmup_steps`，否则直接 `TypeError`。

---

## 4. 四组对比（77 题，全部来自 11 个留出模板）

评测集 `data/holdout_eval.jsonl`：每个留出模板 7 种问法（原问法 + LLM 改写 4 条 + 换时间窗 2 条），参考答案由模板渲染并验证可执行。

判分与线上完全一致（`app/eval/run_golden.py::same_result`）：执行结果集比对，不是 SQL 字符串比对。

| 配置 | 正确 | 准确率 |
|---|---|---|
| base（1.5B，无 RAG） | 0 / 77 | **0%** |
| base + RAG | 0 / 77 | **0%** |
| **LoRA（无 RAG）** | **27 / 77** | **35.1%** |
| LoRA + RAG | 16 / 77 | 20.8% |

### 怎么读这张表

1. **base 一条都写不对**，不是「写得差一点」，而是编造不存在的表（例如 `JOIN products`）——它压根不知道这套库有哪些表。这正是微调要补的：schema 知识。
2. **LoRA 从 0 → 35.1%**，说明「模板 + schema 的领域适配」确实被学进去了；剩下 65% 主要是跨模板泛化（留出模板的 SQL 结构没见过）和多指标/多表 JOIN。
3. **RAG 对微调模型是负收益（35.1% → 20.8%）**，这是个反直觉但可解释的结果：
   - 训练时输入只有 system + 问题，**没有检索上下文**，推理时突然多出 1200 字符，分布不一致；
   - 1.5B 的上下文容量有限，长检索文本把 schema 挤出了注意力；
   - 线上大模型（DeepSeek）RAG 是正收益（12.5% → 90.6%），说明**RAG 的收益取决于模型能否消化上下文**，不是无条件的。
4. 小模型典型错误：引号不闭合（`FORMAT(DATE_ADD('2026-12-31', INTERVAL (WEEKDAY('`）、多算一列、把 `YEARWEEK` 写成不存在的函数。评测脚本对这些一律记为失败，不做「宽容修正」。

---

## 5. 复现

```bash
# 1) 合成数据（需要 LLM API）
python -m finetune.synth

# 2) 训练
python -m finetune.train_qlora --epochs 3

# 3) 造留出评测集
python -m finetune.make_holdout_eval

# 4) 四组对比
python -m finetune.eval_lora --base --items finetune/data/holdout_eval.jsonl --out finetune/out/h_base.json
python -m finetune.eval_lora --base --rag --items finetune/data/holdout_eval.jsonl --out finetune/out/h_base_rag.json
python -m finetune.eval_lora --adapter finetune/out/lora-sql --items finetune/data/holdout_eval.jsonl --out finetune/out/h_lora.json
python -m finetune.eval_lora --adapter finetune/out/lora-sql --rag --items finetune/data/holdout_eval.jsonl --out finetune/out/h_lora_rag.json
```

---

## 6. 局限（写在前面，别等面试官问）

- 77 条评测全部来自 11 个留出模板，**只测了模板级泛化，没测真实用户的长尾问法**；
- 单卡单次训练，没有多 seed，35.1% 的置信区间大约 ±11pt，不宜说成精确值；
- 没做 reranker 微调：`train_reranker.py` 已写好并 dry-run 通过（81 对正负例），但 81 条训练样本对 CrossEncoder 太少，训出来在同一个 81 题上评估必然过拟合，**数字不可信，所以本轮不跑**——这是取舍，不是遗漏；
- 线上仍用 DeepSeek（约 90% exec_accuracy），微调模型只作为「成本敏感场景的降级候选」，这个定位在 README 里也写明了。