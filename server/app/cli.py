"""Command-line maintenance tasks.

    python -m app.cli new-api-key      Revoke the current API key and print a new one.
"""

import sys

from sqlalchemy.orm import Session

from app.db import make_engine
from app.security import issue_api_key
from app.settings import Settings


def main(argv: list[str]) -> int:
    if argv != ["new-api-key"]:
        print(__doc__)
        return 2
    engine = make_engine(Settings.from_env().database_path)
    with Session(engine) as session:
        raw, key = issue_api_key(session)
    engine.dispose()
    print(raw)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
