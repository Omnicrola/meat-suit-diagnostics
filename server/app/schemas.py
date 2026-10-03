from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.question_types import QuestionType
from app.timeutil import UtcTimestamp

ResponseStatus = Literal["answered", "skipped", "missed"]

MAX_UPLOAD_BATCH = 500


class CheckinInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    time_local: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    days_of_week: list[int] = Field(min_length=1, max_length=7)
    question_ids: list[int] = Field(min_length=1)
    expires_after_minutes: int = Field(default=120, ge=1, le=1440)
    enabled: bool = True

    @field_validator("name")
    @classmethod
    def _strip_name(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be blank")
        return v.strip()

    @field_validator("days_of_week")
    @classmethod
    def _check_days(cls, v: list[int]) -> list[int]:
        if len(set(v)) != len(v) or not all(1 <= d <= 7 for d in v):
            raise ValueError("days_of_week must be unique ISO weekdays 1 (Mon) to 7 (Sun)")
        return sorted(v)

    @field_validator("question_ids")
    @classmethod
    def _check_questions(cls, v: list[int]) -> list[int]:
        if len(set(v)) != len(v):
            raise ValueError("a question can appear only once in a check-in")
        return v


# --- /config ------------------------------------------------------------------


class QuestionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    version: int
    text: str
    type: QuestionType
    config: dict[str, Any]


class QuestionVersionOut(QuestionOut):
    deleted: bool
    created_at: UtcTimestamp


class CheckinOut(BaseModel):
    id: int
    name: str
    time_local: str
    days_of_week: list[int]
    expires_after_minutes: int
    question_ids: list[int]


class ConfigOut(BaseModel):
    version: int
    questions: list[QuestionOut]
    checkins: list[CheckinOut]


# --- /responses ---------------------------------------------------------------


class ResponseIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    question_id: int
    question_version: int
    checkin_id: int | None = None
    instance_id: UUID | None = None
    status: ResponseStatus
    value: Any = None
    scheduled_for: UtcTimestamp | None = None
    answered_at: UtcTimestamp

    @model_validator(mode="after")
    def _value_matches_status(self):
        if self.status == "answered" and self.value is None:
            raise ValueError("an answered response needs a value")
        if self.status != "answered" and self.value is not None:
            raise ValueError(f"a {self.status} response must not have a value")
        return self


class UploadIn(BaseModel):
    # Items are validated one by one so a single bad response doesn't block the rest of the batch.
    responses: list[dict[str, Any]] = Field(max_length=MAX_UPLOAD_BATCH)


class Rejected(BaseModel):
    id: str | None
    error: str


class UploadResult(BaseModel):
    accepted: list[str]
    duplicates: list[str]
    rejected: list[Rejected]


class ResponseOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    question_id: int
    question_version: int
    checkin_id: int | None
    instance_id: str | None
    status: ResponseStatus
    value: Any
    scheduled_for: UtcTimestamp | None
    answered_at: UtcTimestamp
    received_at: UtcTimestamp


class ResponseList(BaseModel):
    responses: list[ResponseOut]
