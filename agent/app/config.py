"""
config.py — 全局配置中心
=====================
为什么要有这一层？
  所有外部依赖（LLM、MySQL、Redis、Milvus）的连接信息集中在这里，
  从 .env 读取。好处：
  1. 换环境只改 .env，不改代码；
  2. 别人拿到项目，看 .env.example 就知道要配什么；
  3. 代码里绝不出现硬编码的 Key / 密码。

教学点：12-Factor 配置管理 —— 配置与代码分离。
"""

import os
import sys
from dataclasses import dataclass, field

from dotenv import load_dotenv

# 配置文件的"家"：
#  - 开发模式：项目根目录（agent/）
#  - PyInstaller 打包后：exe 所在目录（部署时把 .env 放在 agent.exe 旁边）
# 教学点：getattr(sys, "frozen") 是 PyInstaller 运行时的标记，
# 此时 sys.executable 指向 exe 本身，__file__ 指向临时解压目录（不可用于找配置文件）。
if getattr(sys, "frozen", False):
    BASE_DIR = os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 加载 exe 所在目录 / 项目根目录下的 .env（不存在也不报错，使用默认值）
load_dotenv(os.path.join(BASE_DIR, ".env"))


def _env(key: str, default: str = "") -> str:
    """读取环境变量，统一入口，方便以后加默认值/校验逻辑。"""
    return os.getenv(key, default)


@dataclass
class Settings:
    # ================= LLM（Agent 的大脑） =================
    # provider: deepseek=真实模型 | mock=本地确定性模拟（无 Key 调试用）
    llm_provider: str = field(default_factory=lambda: _env("LLM_PROVIDER", "deepseek"))
    deepseek_api_key: str = field(default_factory=lambda: _env("DEEPSEEK_API_KEY", ""))
    deepseek_base_url: str = field(default_factory=lambda: _env("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"))
    # 注意：文档里的 "deepseek-v4.1-flash" 以 DeepSeek 官方实际可用的模型名为准
    deepseek_model: str = field(default_factory=lambda: _env("DEEPSEEK_MODEL", "deepseek-flash"))
    # 温度：SQL 生成要低温度（确定性优先）
    llm_temperature: float = field(default_factory=lambda: float(_env("LLM_TEMPERATURE", "0")))

    # ================= RAG（SQL 模板知识库） =================
    rag_top_k: int = field(default_factory=lambda: int(_env("RAG_TOP_K", "4")))

    # ================= MySQL（只读执行层） =================
    # 生产规范：必须使用只读账号 agent_ro，从权限上杜绝写操作
    mysql_host: str = field(default_factory=lambda: _env("MYSQL_HOST", "127.0.0.1"))
    mysql_port: int = field(default_factory=lambda: int(_env("MYSQL_PORT", "3306")))
    mysql_user: str = field(default_factory=lambda: _env("MYSQL_USER", "agent_ro"))
    mysql_password: str = field(default_factory=lambda: _env("MYSQL_PASSWORD", ""))
    mysql_db: str = field(default_factory=lambda: _env("MYSQL_DB", "ecommerce"))

    # ================= Redis（记忆 / 缓存 / 限流） =================
    redis_url: str = field(default_factory=lambda: _env("REDIS_URL", "redis://127.0.0.1:6379/0"))

    # ================= PostgreSQL（历史对话归档） =================
    # 与 Redis 的分工：Redis=短期工作记忆（指代消解），PG=永久历史（回放/审计）。
    # 部署在虚拟机 Docker（postgres:16），本机直连；不可达时历史功能降级（不影响对话）。
    pg_host: str = field(default_factory=lambda: _env("PG_HOST", "192.168.96.129"))
    pg_port: int = field(default_factory=lambda: int(_env("PG_PORT", "5432")))
    pg_user: str = field(default_factory=lambda: _env("PG_USER", "raguser"))
    pg_password: str = field(default_factory=lambda: _env("PG_PASSWORD", ""))
    pg_db: str = field(default_factory=lambda: _env("PG_DB", "rag_db"))

    # ================= Milvus（SQL 模板向量库） =================
    # 注意：Milvus 部署在虚拟机（Docker），虚拟机开机后填虚拟机 IP 即可启用；
    # 不可达时自动降级到本地向量检索（milvus_use_local_fallback 控制）
    milvus_host: str = field(default_factory=lambda: _env("MILVUS_HOST", "192.168.96.129"))
    milvus_port: int = field(default_factory=lambda: int(_env("MILVUS_PORT", "19530")))
    milvus_collection: str = field(default_factory=lambda: _env("MILVUS_COLLECTION", "sql_templates"))
    milvus_use_local_fallback: bool = field(
        default_factory=lambda: _env("MILVUS_USE_LOCAL_FALLBACK", "true").lower() == "true"
    )

    # ================= 业务与安全参数 =================
    max_rows: int = field(default_factory=lambda: int(_env("MAX_ROWS", "1000")))  # 结果行数上限
    query_timeout: int = field(default_factory=lambda: int(_env("QUERY_TIMEOUT", "10")))  # 秒
    session_ttl: int = field(default_factory=lambda: int(_env("SESSION_TTL", "86400")))  # 24h
    sql_cache_ttl: int = field(default_factory=lambda: int(_env("SQL_CACHE_TTL", "300")))  # 5min
    rate_limit_per_min: int = field(default_factory=lambda: int(_env("RATE_LIMIT", "30")))
    sql_retry_times: int = field(default_factory=lambda: int(_env("SQL_RETRY_TIMES", "3")))  # 自纠错轮数
    audit_log_max: int = field(default_factory=lambda: int(_env("AUDIT_LOG_MAX", "500")))

    # ================= 数据时间锚点 =================
    # 数据不是实时同步的：Agent 计算"上个月/最近7天"时，应基于数据里最大的 order_time，
    # 而不是系统今天。否则对历史数据提问会得到空结果。默认自动探测。
    data_anchor_override: str = field(default_factory=lambda: _env("DATA_ANCHOR_OVERRIDE", ""))

    @property
    def database_url(self) -> str:
        """SQLAlchemy 连接串，只读账号 + utf8mb4。"""
        return (
            f"mysql+pymysql://{self.mysql_user}:{self.mysql_password}"
            f"@{self.mysql_host}:{self.mysql_port}/{self.mysql_db}?charset=utf8mb4"
        )

    @property
    def pg_database_url(self) -> str:
        """PostgreSQL 历史归档连接串（psycopg3 驱动）。
        注意：用户名/密码必须 URL 编码 —— 密码里的 @ : / 等字符
        会干扰连接串解析（Rag@123456 的 @ 会被当成主机分隔符）。"""
        from urllib.parse import quote_plus

        return (
            f"postgresql+psycopg://{quote_plus(self.pg_user)}:{quote_plus(self.pg_password)}"
            f"@{self.pg_host}:{self.pg_port}/{self.pg_db}"
        )


settings = Settings()
