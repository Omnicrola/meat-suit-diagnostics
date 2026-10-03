import hashlib
import secrets
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import APIKeyHeader
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.db import SessionDep
from app.models import ApiKey
from app.timeutil import utcnow

API_KEY_PREFIX = "msd_"

_api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


def hash_api_key(raw_key: str) -> str:
    # The key has 256 bits of entropy, so a fast hash is sufficient (unlike passwords).
    return hashlib.sha256(raw_key.encode()).hexdigest()


def issue_api_key(session: Session) -> tuple[str, ApiKey]:
    """Revoke any active key and create a new one. Returns the raw key, which is never stored."""
    now = utcnow()
    session.execute(update(ApiKey).where(ApiKey.revoked_at.is_(None)).values(revoked_at=now))
    raw = API_KEY_PREFIX + secrets.token_urlsafe(32)
    key = ApiKey(key_hash=hash_api_key(raw), prefix=raw[:8], created_at=now)
    session.add(key)
    session.commit()
    return raw, key


def revoke_api_keys(session: Session) -> None:
    session.execute(update(ApiKey).where(ApiKey.revoked_at.is_(None)).values(revoked_at=utcnow()))
    session.commit()


def get_active_api_key(session: Session) -> ApiKey | None:
    return session.scalar(select(ApiKey).where(ApiKey.revoked_at.is_(None)))


def require_api_key(
    session: SessionDep, raw_key: Annotated[str | None, Depends(_api_key_header)]
) -> ApiKey:
    key = None
    if raw_key:
        key = session.scalar(
            select(ApiKey).where(ApiKey.key_hash == hash_api_key(raw_key), ApiKey.revoked_at.is_(None))
        )
    if key is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing or invalid API key")
    key.last_used_at = utcnow()
    session.commit()
    return key
