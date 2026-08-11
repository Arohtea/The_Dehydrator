import json

from prompts.logic_flaw import LOGIC_FLAW_PROMPT
from services.llm import get_chat_model
from services.model_config import AIModelConfig
from services.stream_publisher import stream_invoke
from services.output_models import LogicFlawResult, parse_and_validate


def detect_logic_flaws(argument_chain: dict, text_config: AIModelConfig,
                       task_id: str = "") -> dict:
    """检测论据链中的逻辑问题。

    Args:
        argument_chain: 待检查的结构化论据链。
        text_config: 本次分析使用的文本模型配置。
        task_id: 分析任务 ID，用于进度流和取消检查。

    Returns:
        结构化逻辑问题检测结果。
    """
    # 漏洞检测与论据提取使用同一份请求级文本模型配置，保证分析结果来自用户选择的模型。
    llm = get_chat_model(text_config)
    # 序列化后的论据链作为不可信输入放入提示词，模型只负责判断，不直接修改原始结构。
    chain_str = json.dumps(argument_chain, ensure_ascii=False)
    # 采用统一的流式调用入口，复用取消检查、进度消息和传输层重试策略。
    text = stream_invoke(llm, LOGIC_FLAW_PROMPT.format(argument_chain=chain_str), task_id, "logic_flaws")
    from services import strip_markdown_json
    # 模型可能用 Markdown 代码块包裹 JSON，清理后再按漏洞结果模型进行结构校验。
    return parse_and_validate(LogicFlawResult, strip_markdown_json(text))
