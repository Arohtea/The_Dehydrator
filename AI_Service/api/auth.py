"""AI Service 的内部服务认证依赖。"""

from secrets import compare_digest
from typing import Annotated

from fastapi import Header, HTTPException, status

from config.settings import settings


async def require_service_token(
    token: Annotated[str | None, Header(alias="X-Service-Token")] = None,
) -> None:
    """校验 Business Service 到 AI Service 的内部服务令牌。

    Args:
        token: 从 `X-Service-Token` Header 读取的内部令牌。

    Raises:
        HTTPException: 配置缺失、请求未携带令牌或令牌校验失败时返回 401。

    Notes:
        使用常量时间比较降低令牌比较产生时序侧信道的风险；该依赖挂在路由
        级别，因此所有文档和分析接口都会经过同一认证入口。
    """
    # 期望值来自 AI Service 自己的受保护配置，不接受调用方通过请求覆盖。
    expected = settings.internal_service_token
    # 缺少服务端配置、请求 Header 或校验不一致都按同一个 401 返回，避免泄露失败原因。
    if not expected or not token or not compare_digest(token, expected):
        # compare_digest 让字符串比较尽量使用常量时间，降低令牌长度信息被推测的风险。
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="内部服务认证失败",
        )
