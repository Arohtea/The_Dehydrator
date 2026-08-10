"""手工验证 OpenAI 兼容文本模型连通性。"""

import argparse
from getpass import getpass

from langchain_openai import ChatOpenAI


def main() -> None:
    parser = argparse.ArgumentParser(description="验证设置页中的文本模型配置")
    parser.add_argument("--model", required=True, help="文本模型名称")
    parser.add_argument("--url", required=True, help="OpenAI 兼容接口根地址")
    args = parser.parse_args()
    api_key = getpass("API Key: ").strip()
    if not api_key:
        raise ValueError("API Key 不能为空")

    llm = ChatOpenAI(
        model=args.model,
        base_url=args.url,
        api_key=api_key,
        temperature=0.1,
    )
    response = llm.invoke("你好，请用一句话介绍你自己。")
    print(response.content)


if __name__ == "__main__":
    main()
