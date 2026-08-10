from secrets import compare_digest
from typing import Annotated

from fastapi import Header, HTTPException, status

from config.settings import settings


async def require_service_token(
    token: Annotated[str | None, Header(alias="X-Service-Token")] = None,
) -> None:
    """校验 Business Service 到 AI Service 的内部服务令牌。"""
    expected = settings.internal_service_token
    if not expected or not token or not compare_digest(token, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="内部服务认证失败",
        )
