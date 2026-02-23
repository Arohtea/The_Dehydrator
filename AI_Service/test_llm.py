"""测试智谱 LLM 连通性"""
from config.settings import settings
from langchain_community.chat_models import ChatZhipuAI

print(f"模型: {settings.zhipuai_model}")
print(f"API Key: {settings.zhipuai_api_key[:8]}...")

llm = ChatZhipuAI(
    model=settings.zhipuai_model,
    api_key=settings.zhipuai_api_key,
    temperature=0.1,
    request_timeout=60,
)

print("正在调用...")
resp = llm.invoke("你好，请用一句话介绍你自己。")
print(f"回复: {resp.content}")
print("测试通过!")
