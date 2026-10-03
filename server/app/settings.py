import os
from dataclasses import dataclass


def _flag(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes")


@dataclass(frozen=True)
class Settings:
    database_path: str = "meatsuit.db"
    # Interactive API docs (/docs, /openapi.json) are unauthenticated, so they are off unless asked for.
    enable_docs: bool = False
    # Admin web app login. Admin login is disabled until both are set.
    admin_username: str | None = None
    admin_password_hash: str | None = None  # Argon2; generate with: python -m app.cli hash-password
    # Signs the admin session cookie. If unset, a random one is generated and sessions end on restart.
    session_secret: str | None = None
    # Mark the session cookie Secure (HTTPS only). Turn off only for local development over plain HTTP.
    secure_cookies: bool = True
    # Server URL put in the phone setup QR code. Defaults to the URL the admin page was loaded from.
    public_url: str | None = None

    @property
    def admin_enabled(self) -> bool:
        return bool(self.admin_username and self.admin_password_hash)

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            database_path=os.environ.get("DATABASE_PATH", cls.database_path),
            enable_docs=_flag("ENABLE_DOCS", False),
            admin_username=os.environ.get("ADMIN_USERNAME") or None,
            admin_password_hash=os.environ.get("ADMIN_PASSWORD_HASH") or None,
            session_secret=os.environ.get("SESSION_SECRET") or None,
            secure_cookies=_flag("SECURE_COOKIES", True),
            public_url=(os.environ.get("PUBLIC_URL") or "").rstrip("/") or None,
        )
