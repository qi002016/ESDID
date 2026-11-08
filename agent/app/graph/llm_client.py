"""
graph/llm_client.py — 结构化输出客户端
======================================
一次 LLM 调用 = 强约束 Prompt + JSON 解析 + Pydantic 校验 + 失败重试一次。

关键取舍：
  - 不开 response_format=json_object（DeepSeek 兼容模式对 schema 支持不稳定），
    而是把 JSON 结构写进 Prompt，解析失败时把**解析错误原文**回喂给模型重试；
  - 重试只做一次：再多就是烧钱，且通常说明 Prompt 或问题本身有歧义。
"""

from __future__ import annotations

import json
import re
from typing import Type, TypeVar

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ValidationError

from app.config import settings

T = TypeVar("T", bound=BaseModel)


def get_llm(temperature: float = 0.0, timeout: int | None = None) -> ChatOpenAI:
    return ChatOpenAI(
        model=settings.deepseek_model,
        base_url=settings.deepseek_base_url,
        api_key=settings.deepseek_api_key,
        temperature=temperature,
        timeout=timeout or settings.llm_timeout,
        max_retries=1,
    )


def _extract_json(text: str) -> str:
    """从可能带 ```json 围栏或前后废话的输出里取出第一个完整 JSON 对象。"""
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fenced:
        return fenced.group(1)
    start = text.find("{")
    if start < 0:
        return ""
    depth = 0
    in_str = False
    esc = False
    for i, ch in enumerate(text[start:], start=start):
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return ""


async def call_structured(
    system: str,
    user: str,
    model_cls: Type[T],
    temperature: float = 0.0,
    retries: int = 1,
) -> tuple[T | None, str | None]:
    """返回 (parsed, error)。error 为 None 表示成功。"""
    llm = get_llm(temperature=temperature)
    messages = [SystemMessage(system), HumanMessage(user)]
    last_error = ""

    for attempt in range(retries + 1):
        try:
            resp = await llm.ainvoke(messages)
        except Exception as e:  # 网络/超时/限流
            return None, f"LLM 调用失败：{type(e).__name__}: {e}"

        raw = (resp.content or "").strip()
        payload = _extract_json(raw)
        if not payload:
            last_error = "输出中没有 JSON 对象"
        else:
            try:
                return model_cls.model_validate_json(payload), None
            except ValidationError as e:
                last_error = f"字段校验失败：{e.errors()[:3]}"
            except json.JSONDecodeError as e:
                last_error = f"JSON 解析失败：{e}"

        if attempt < retries:
            # 把错误原文回喂，比"请重试"有效得多
            messages.append(HumanMessage(
                f"你上一次的输出无法解析：{last_error}。请只输出一个符合契约的 JSON 对象，不要任何解释。"
            ))

    return None, last_error