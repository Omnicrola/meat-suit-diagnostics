# Meat Suit Diagnostics — server

A FastAPI app that serves the phone API (`/api/v1`) and the admin web app (`/admin`). See [../docs/DESIGN.md](../docs/DESIGN.md).

## Local development

```bash
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"     # .venv/bin/python on macOS/Linux
.venv/Scripts/python -m pytest
```

Run a local dev server:

```bash
.venv/Scripts/python scripts/dev_server.py
```

This uses `dev.db`, plain HTTP and a fixed dev-only admin login (see the script). Then open:
- http://localhost:8000/admin for the admin app.
- http://localhost:8000/docs for the API docs. Click **Authorize** and paste a key from the admin app's API key page.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_PATH` | `meatsuit.db` (`/data/meatsuit.db` in Docker) | SQLite file. Must be on the Railway volume in production. |
| `ADMIN_USERNAME` | — | Admin login. Admin login is disabled unless this and the hash are both set. |
| `ADMIN_PASSWORD_HASH` | — | Argon2 hash. Generate with `python -m app.cli hash-password`. |
| `SESSION_SECRET` | random per start | Signs the admin session cookie. Set it, or every restart signs you out. Generate with `python -c "import secrets; print(secrets.token_urlsafe(32))"`. |
| `PUBLIC_URL` | URL of the admin page | Server URL placed in the phone setup QR code, e.g. `https://msd.up.railway.app`. |
| `SECURE_COOKIES` | on | Marks the session cookie HTTPS-only. Turn off only for local plain-HTTP development. |
| `ENABLE_DOCS` | off | Serves `/docs` and `/openapi.json`. These are unauthenticated, so leave off in production. |

## Maintenance commands

```bash
python -m app.cli hash-password    # prompt for a password, print the ADMIN_PASSWORD_HASH value
python -m app.cli new-api-key      # revoke the current phone key and print a new one
```

## Schema changes

Edit `app/models.py`, then:

```bash
DATABASE_PATH=scratch.db .venv/Scripts/alembic upgrade head
DATABASE_PATH=scratch.db .venv/Scripts/alembic revision --autogenerate -m "describe change"
```

Review the generated file in `migrations/versions/`. `tests/test_migrations.py` fails if migrations and models disagree.
