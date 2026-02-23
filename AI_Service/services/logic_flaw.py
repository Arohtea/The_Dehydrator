import json
from langchain_community.chat_models import ChatZhipuAI
from config.settings import settings
from prompts.logic_flaw import LOGIC_FLAW_PROMPT
from services.stream_publisher import stream_invoke


def _get_llm(api_key: str | None = None, model: str | None = None):
    return ChatZhipuAI(
        model=model or settings.zhipuai_model,
        api_key=api_key or settings.zhipuai_api_key,
        temperature=0.1,
        streaming=True,
    )


def detect_logic_flaws(argument_chain: dict, task_id: str = "",
                       api_key: str | None = None, model: str | None = None) -> dict:
    llm = _get_llm(api_key, model)
    chain_str = json.dumps(argument_chain, ensure_ascii=False)
    text = stream_invoke(llm, LOGIC_FLAW_PROMPT.format(argument_chain=chain_str), task_id, "logic_flaws")
    from services import strip_markdown_json
    try:
        return json.loads(strip_markdown_json(text))
    except json.JSONDecodeError:
        return {"raw": text}
