"""Command-line maintenance tasks.

    python -m app.cli new-api-key      Revoke the current API key and print a new one.
    python -m app.cli hash-password    Prompt for a password and print its hash for ADMIN_PASSWORD_HASH.
"""

import getpass
import sys

from sqlalchemy.orm import Session

from app.db import make_engine
from app.security import issue_api_key
from app.settings import Settings

MIN_PASSWORD_LENGTH = 12


def new_api_key() -> int:
    engine = make_engine(Settings.from_env().database_path)
    with Session(engine) as session:
        raw, _ = issue_api_key(session)
    engine.dispose()
    print(raw)
    return 0


def hash_password() -> int:
    from app.admin.auth import hash_password as _hash

    password = getpass.getpass("Admin password: ")
    if len(password) < MIN_PASSWORD_LENGTH:
        print(f"Use at least {MIN_PASSWORD_LENGTH} characters.", file=sys.stderr)
        return 1
    if getpass.getpass("Repeat password: ") != password:
        print("Passwords don't match.", file=sys.stderr)
        return 1
    print(_hash(password))
    return 0


COMMANDS = {"new-api-key": new_api_key, "hash-password": hash_password}


def main(argv: list[str]) -> int:
    if len(argv) != 1 or argv[0] not in COMMANDS:
        print(__doc__)
        return 2
    return COMMANDS[argv[0]]()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
