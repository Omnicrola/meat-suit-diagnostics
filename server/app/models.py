from datetime import datetime
from typing import Any

from sqlalchemy import JSON, ForeignKey, ForeignKeyConstraint, Index, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base
from app.timeutil import UtcDateTime


class Question(Base):
    """One version of a question. Rows are never updated: edits and deletes insert a new version."""

    __tablename__ = "questions"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=False)
    version: Mapped[int] = mapped_column(primary_key=True, autoincrement=False)
    text: Mapped[str]
    type: Mapped[str] = mapped_column(String(20))
    config: Mapped[dict[str, Any]] = mapped_column(JSON)
    deleted: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime)


class Checkin(Base):
    __tablename__ = "checkins"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str]
    time_local: Mapped[str] = mapped_column(String(5))  # "HH:MM"
    days_of_week: Mapped[list[int]] = mapped_column(JSON)  # ISO weekdays, 1 = Monday
    expires_after_minutes: Mapped[int] = mapped_column(default=120)
    enabled: Mapped[bool] = mapped_column(default=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime)
    updated_at: Mapped[datetime] = mapped_column(UtcDateTime)
    deleted_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    questions: Mapped[list["CheckinQuestion"]] = relationship(
        order_by="CheckinQuestion.position", cascade="all, delete-orphan"
    )


class CheckinQuestion(Base):
    """Membership of a question in a check-in. References the question id, so the current version is always asked."""

    __tablename__ = "checkin_questions"

    checkin_id: Mapped[int] = mapped_column(ForeignKey("checkins.id"), primary_key=True)
    question_id: Mapped[int] = mapped_column(primary_key=True, autoincrement=False)
    position: Mapped[int]


class ResponseRecord(Base):
    __tablename__ = "responses"
    __table_args__ = (
        ForeignKeyConstraint(["question_id", "question_version"], ["questions.id", "questions.version"]),
        Index("ix_responses_answered_at", "answered_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)  # UUID generated on the phone
    question_id: Mapped[int]
    question_version: Mapped[int]
    checkin_id: Mapped[int | None] = mapped_column(ForeignKey("checkins.id"))
    instance_id: Mapped[str | None] = mapped_column(String(36))
    status: Mapped[str] = mapped_column(String(10))  # answered | skipped | missed
    value: Mapped[Any] = mapped_column(JSON(none_as_null=True), nullable=True)
    scheduled_for: Mapped[datetime | None] = mapped_column(UtcDateTime)
    answered_at: Mapped[datetime] = mapped_column(UtcDateTime)
    received_at: Mapped[datetime] = mapped_column(UtcDateTime)


class ApiKey(Base):
    __tablename__ = "api_keys"

    id: Mapped[int] = mapped_column(primary_key=True)
    key_hash: Mapped[str] = mapped_column(String(64), unique=True)
    prefix: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(UtcDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    last_used_at: Mapped[datetime | None] = mapped_column(UtcDateTime)


class Meta(Base):
    __tablename__ = "meta"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str]
