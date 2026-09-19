"""
pytest 根配置：
  1. 确保项目根目录在 sys.path，测试才能 import app；
  2. ★ 强制测试走 Mock 模式 —— 在 import app.config 之前设置环境变量：
     测试必须"确定性、离线、零成本"，绝不能因为 .env 里配了 DeepSeek Key
     就真的去调模型。这也是为什么配置要集中且可被环境变量覆盖。
"""
import os
import sys

# 必须在 import app 之前设置（load_dotenv 不会覆盖已存在的环境变量）
os.environ["LLM_PROVIDER"] = "mock"
os.environ["DEEPSEEK_API_KEY"] = ""
os.environ["MILVUS_USE_LOCAL_FALLBACK"] = "true"
# 测试不连外部 PostgreSQL（历史归档关闭，保证测试离线、可复现）
os.environ["PG_HOST"] = ""

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
