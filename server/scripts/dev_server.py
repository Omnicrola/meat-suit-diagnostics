"""Run the server locally for development: python scripts/dev_server.py [port]

Uses dev.db in the server directory, plain HTTP, API docs on, and a fixed dev-only admin login
(username "admin", password DEV_ADMIN_PASSWORD below). Never use these settings in production.
"""

import os
import subprocess
import sys
from pathlib import Path

DEV_ADMIN_PASSWORD = "dev-password-local-only"

SERVER_DIR = Path(__file__).resolve().parent.parent


def main() -> None:
    port = sys.argv[1] if len(sys.argv) > 1 else "8000"
    sys.path.insert(0, str(SERVER_DIR))
    from app.admin.auth import hash_password

    env = os.environ | {
        "DATABASE_PATH": str(SERVER_DIR / "dev.db"),
        "ADMIN_USERNAME": "admin",
        "ADMIN_PASSWORD_HASH": hash_password(DEV_ADMIN_PASSWORD),
        "SESSION_SECRET": "dev-session-secret",
        "SECURE_COOKIES": "0",
        "ENABLE_DOCS": "1",
    }
    subprocess.run([sys.executable, "-m", "alembic", "upgrade", "head"], cwd=SERVER_DIR, env=env, check=True)
    subprocess.run(
        [sys.executable, "-m", "uvicorn", "app.main:create_app", "--factory", "--reload", "--port", port],
        cwd=SERVER_DIR, env=env,
    )


if __name__ == "__main__":
    main()
