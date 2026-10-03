import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_path: str = "meatsuit.db"
    # Interactive API docs (/docs, /openapi.json) are unauthenticated, so they are off unless asked for.
    enable_docs: bool = False

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            database_path=os.environ.get("DATABASE_PATH", cls.database_path),
            enable_docs=os.environ.get("ENABLE_DOCS", "").lower() in ("1", "true", "yes"),
        )
