# Meat Suit Diagnostics — server

FastAPI app serving the phone API (`/api/v1`) and, later, the admin web app. See [../docs/DESIGN.md](../docs/DESIGN.md).

## Local development

```bash
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"     # .venv/bin/python on macOS/Linux
.venv/Scripts/python -m pytest
```

Run locally (creates `meatsuit.db` in the current directory):

```bash
.venv/Scripts/alembic upgrade head
.venv/Scripts/python -m app.cli new-api-key
ENABLE_DOCS=1 .venv/Scripts/uvicorn app.main:create_app --factory --reload
```

Then open http://127.0.0.1:8000/docs, click **Authorize**, and paste the key.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_PATH` | `meatsuit.db` (`/data/meatsuit.db` in Docker) | SQLite file. Must be on the Railway volume in production. |
| `ENABLE_DOCS` | off | Serves `/docs` and `/openapi.json`. These are unauthenticated, so leave off in production. |

## Schema changes

Edit `app/models.py`, then:

```bash
DATABASE_PATH=scratch.db .venv/Scripts/alembic upgrade head
DATABASE_PATH=scratch.db .venv/Scripts/alembic revision --autogenerate -m "describe change"
```

Review the generated file in `migrations/versions/`. `tests/test_migrations.py` fails if migrations and models disagree.
