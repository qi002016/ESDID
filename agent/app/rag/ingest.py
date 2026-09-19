"""
rag/ingest.py — SQL 模板入库脚本
================================
用法：python -m app.rag.ingest
作用：把 templates.py 里的预置模板灌入向量库（Milvus 或本地降级）。
     服务启动时也会自动执行一次；模板/口径变更后手动重灌。
"""

from app.rag.vector_store import get_store_mode, init_templates

if __name__ == "__main__":
    n = init_templates()
    print(f"[ingest] 完成：{n} 个 SQL 模板已入库，存储模式 = {get_store_mode()}")
