from logging.config import fileConfig

from alembic import context

import app.models  # noqa: F401  (registers tables on Base.metadata)
from app.db import Base, make_engine
from app.settings import Settings
from app.timeutil import UtcDateTime

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def render_item(type_, obj, autogen_context):
    # Migrations describe storage only, so render custom column types as their underlying SQL type.
    if type_ == "type" and isinstance(obj, UtcDateTime):
        return "sa.String(length=24)"
    return False


def run_migrations_online() -> None:
    # The database location comes from DATABASE_PATH, same as the app.
    engine = make_engine(Settings.from_env().database_path)
    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,  # SQLite can't ALTER most things; batch mode recreates tables instead
            render_item=render_item,
        )
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


if context.is_offline_mode():
    raise SystemExit("Offline migrations are not supported; run against the database.")
run_migrations_online()
