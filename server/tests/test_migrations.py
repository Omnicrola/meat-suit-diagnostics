from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext

import app.models  # noqa: F401
from app.db import Base, make_engine

SERVER_DIR = Path(__file__).resolve().parent.parent


def test_migrations_match_models(tmp_path, monkeypatch):
    db_path = tmp_path / "migrated.db"
    monkeypatch.setenv("DATABASE_PATH", str(db_path))
    command.upgrade(Config(str(SERVER_DIR / "alembic.ini")), "head")

    engine = make_engine(str(db_path))
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn), Base.metadata)
    engine.dispose()
    assert diff == []
