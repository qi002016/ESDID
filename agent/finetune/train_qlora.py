"""
finetune/train_qlora.py — Qwen2.5-Coder-1.5B + LoRA（8G 笔记本可跑）

为什么是 1.5B 而不是 7B：
  RTX 4060 Laptop 只有 8G 显存。7B 全参需要 ~28G；7B + QLoRA 在 Windows 上
  又依赖 bitsandbytes（Windows 支持差）。1.5B fp16 + LoRA 峰值约 5~6G，稳。

超参（来自小数据量 SFT 的经验值，不是抄默认）：
  r=16 / alpha=32 / dropout=0.05 / lr=2e-4 / 3 epoch / batch=1 × grad_accum=8
  max_len=1536（SQL 任务很少超长，短序列能多塞样本）

用法：
  python -m finetune.train_qlora --data finetune/data --out finetune/out/lora-sql
  python -m finetune.train_qlora --dry-run     # 只校验依赖与显存，不训练
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

BASE_MODEL = "Qwen/Qwen2.5-Coder-1.5B-Instruct"


def check_env() -> None:
    import torch

    print(f"torch {torch.__version__}  cuda={torch.cuda.is_available()}")
    if torch.cuda.is_available():
        free, total = torch.cuda.mem_get_info()
        print(f"GPU: {torch.cuda.get_device_name(0)}  free={free / 2**30:.1f}G / total={total / 2**30:.1f}G")


def load_rows(data_dir: Path) -> tuple[list[dict], list[dict]]:
    def read(name: str) -> list[dict]:
        path = data_dir / f"{name}.jsonl"
        if not path.exists():
            raise SystemExit(f"缺少 {path}，先跑 python -m finetune.synth")
        return [json.loads(line) for line in path.open(encoding="utf-8")]

    train, val = read("train"), read("val")
    print(f"train={len(train)}  val={len(val)}")
    return train, val


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="finetune/data")
    ap.add_argument("--out", default="finetune/out/lora-sql")
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--max-len", type=int, default=1536)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    check_env()
    train_rows, val_rows = load_rows(ROOT / args.data)
    if args.dry_run:
        print("dry-run：依赖与数据 OK")
        return

    import torch
    from datasets import Dataset
    from peft import LoraConfig, get_peft_model
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        DataCollatorForSeq2Seq,
        Trainer,
        TrainingArguments,
    )

    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        torch_dtype=torch.float16,
        device_map={"": 0},
        trust_remote_code=True,
    )
    model.config.use_cache = False

    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    def encode(row: dict) -> dict:
        prompt_ids = tokenizer(row["prompt"] + "\n", add_special_tokens=False)["input_ids"]
        answer_ids = tokenizer(row["sql"] + tokenizer.eos_token, add_special_tokens=False)["input_ids"]
        input_ids = (prompt_ids + answer_ids)[: args.max_len]
        labels = ([-100] * len(prompt_ids) + answer_ids)[: args.max_len]
        return {"input_ids": input_ids, "labels": labels, "attention_mask": [1] * len(input_ids)}

    train_ds = Dataset.from_list([encode(r) for r in train_rows])
    val_ds = Dataset.from_list([encode(r) for r in val_rows])

    steps_per_epoch = max(1, len(train_ds) // (args.batch * args.grad_accum))
    total_steps = int(steps_per_epoch * args.epochs)
    warmup_steps = max(1, int(total_steps * 0.05))

    training_args = TrainingArguments(
        output_dir=str(ROOT / args.out),
        per_device_train_batch_size=args.batch,
        gradient_accumulation_steps=args.grad_accum,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=warmup_steps,
        logging_steps=10,
        save_strategy="epoch",
        eval_strategy="epoch",
        fp16=True,
        gradient_checkpointing=True,
        optim="adamw_torch",
        report_to=[],
        remove_unused_columns=False,
        dataloader_num_workers=0,
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True),
    )
    trainer.train()
    trainer.save_model(str(ROOT / args.out))
    tokenizer.save_pretrained(str(ROOT / args.out))
    print(f"saved -> {ROOT / args.out}")


if __name__ == "__main__":
    main()