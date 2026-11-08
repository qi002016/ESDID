# Agent 服务镜像：FastAPI + LangChain
# 说明：Milvus/Redis/Postgres 由 docker-compose 提供，本镜像只打包应用代码。
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# 先装依赖，利用 layer 缓存
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app

EXPOSE 8000

# 单 worker：Agent 是有状态 SSE 流，先保证行为可预期；横向扩展见 README
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]