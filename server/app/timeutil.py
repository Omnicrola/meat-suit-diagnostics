from datetime import datetime, timezone
from typing import Annotated

from pydantic import AwareDatetime, PlainSerializer
from sqlalchemy import String
from sqlalchemy.types import TypeDecorator

# Fixed-width format (millisecond precision) so that string comparison in SQLite is chronological.
_STORAGE_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def format_utc(dt: datetime) -> str:
    if dt.tzinfo is None:
        raise ValueError("naive datetimes are not allowed; include a UTC offset")
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def parse_utc(value: str) -> datetime:
    return datetime.strptime(value, _STORAGE_FORMAT).replace(tzinfo=timezone.utc)


class UtcDateTime(TypeDecorator):
    """Timezone-aware datetime stored as a UTC ISO 8601 string, e.g. 2026-10-03T01:04:12.123Z."""

    impl = String(24)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        return None if value is None else format_utc(value)

    def process_result_value(self, value, dialect):
        return None if value is None else parse_utc(value)


# Pydantic field type: requires an offset on input, always serializes as UTC with a Z suffix.
UtcTimestamp = Annotated[AwareDatetime, PlainSerializer(format_utc, return_type=str, when_used="json")]
