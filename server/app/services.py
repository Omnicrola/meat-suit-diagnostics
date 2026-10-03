"""Operations on questions and check-ins, shared by the API and the admin web app.

Any change that affects what the phone sees bumps config_version, which is the /config ETag.
"""

from typing import Any

from sqlalchemy import Select, func, select
from sqlalchemy.orm import Session

from app.models import Checkin, CheckinQuestion, Meta, Question
from app.question_types import validate_config
from app.schemas import CheckinInput
from app.timeutil import utcnow

_CONFIG_VERSION_KEY = "config_version"


class NotFoundError(LookupError):
    pass


# --- config version ---------------------------------------------------------


def get_config_version(session: Session) -> int:
    meta = session.get(Meta, _CONFIG_VERSION_KEY)
    return int(meta.value) if meta else 0


def bump_config_version(session: Session) -> int:
    meta = session.get(Meta, _CONFIG_VERSION_KEY)
    if meta is None:
        meta = Meta(key=_CONFIG_VERSION_KEY, value="0")
        session.add(meta)
    meta.value = str(int(meta.value) + 1)
    return int(meta.value)


# --- questions --------------------------------------------------------------


def current_questions_query(include_deleted: bool = False) -> Select[tuple[Question]]:
    """The highest version of each question."""
    latest = (
        select(Question.id, func.max(Question.version).label("version"))
        .group_by(Question.id)
        .subquery()
    )
    query = (
        select(Question)
        .join(latest, (Question.id == latest.c.id) & (Question.version == latest.c.version))
        .order_by(Question.id)
    )
    if not include_deleted:
        query = query.where(Question.deleted.is_(False))
    return query


def get_current_question(session: Session, question_id: int) -> Question | None:
    """The highest version of a question, which may be a deleted marker."""
    return session.scalar(
        select(Question).where(Question.id == question_id).order_by(Question.version.desc()).limit(1)
    )


def _get_live_question(session: Session, question_id: int) -> Question:
    current = get_current_question(session, question_id)
    if current is None or current.deleted:
        raise NotFoundError(f"question {question_id} not found")
    return current


def create_question(session: Session, text: str, qtype: str, config: dict[str, Any]) -> Question:
    config = validate_config(qtype, config)
    next_id = (session.scalar(select(func.max(Question.id))) or 0) + 1
    question = Question(
        id=next_id, version=1, text=_require_text(text), type=qtype, config=config,
        deleted=False, created_at=utcnow(),
    )
    session.add(question)
    bump_config_version(session)
    session.commit()
    return question


def update_question(
    session: Session, question_id: int, text: str, qtype: str, config: dict[str, Any]
) -> Question:
    current = _get_live_question(session, question_id)
    config = validate_config(qtype, config)
    question = Question(
        id=question_id, version=current.version + 1, text=_require_text(text), type=qtype,
        config=config, deleted=False, created_at=utcnow(),
    )
    session.add(question)
    bump_config_version(session)
    session.commit()
    return question


def delete_question(session: Session, question_id: int) -> Question:
    """Soft delete: add a new version marked deleted and remove the question from all check-ins."""
    current = _get_live_question(session, question_id)
    marker = Question(
        id=question_id, version=current.version + 1, text=current.text, type=current.type,
        config=current.config, deleted=True, created_at=utcnow(),
    )
    session.add(marker)
    for membership in session.scalars(select(CheckinQuestion).where(CheckinQuestion.question_id == question_id)):
        session.delete(membership)
    bump_config_version(session)
    session.commit()
    return marker


def _require_text(text: str) -> str:
    text = text.strip()
    if not text:
        raise ValueError("question text must not be empty")
    return text


# --- check-ins --------------------------------------------------------------


def active_checkins_query() -> Select[tuple[Checkin]]:
    return (
        select(Checkin)
        .where(Checkin.deleted_at.is_(None), Checkin.enabled.is_(True))
        .order_by(Checkin.time_local, Checkin.id)
    )


def create_checkin(
    session: Session,
    name: str,
    time_local: str,
    days_of_week: list[int],
    question_ids: list[int],
    expires_after_minutes: int = 120,
    enabled: bool = True,
) -> Checkin:
    now = utcnow()
    checkin = Checkin(created_at=now, updated_at=now, deleted_at=None)
    _apply_checkin(session, checkin, name, time_local, days_of_week, question_ids, expires_after_minutes, enabled)
    session.add(checkin)
    bump_config_version(session)
    session.commit()
    return checkin


def update_checkin(
    session: Session,
    checkin_id: int,
    name: str,
    time_local: str,
    days_of_week: list[int],
    question_ids: list[int],
    expires_after_minutes: int,
    enabled: bool,
) -> Checkin:
    checkin = _get_live_checkin(session, checkin_id)
    _apply_checkin(session, checkin, name, time_local, days_of_week, question_ids, expires_after_minutes, enabled)
    checkin.updated_at = utcnow()
    bump_config_version(session)
    session.commit()
    return checkin


def delete_checkin(session: Session, checkin_id: int) -> None:
    checkin = _get_live_checkin(session, checkin_id)
    checkin.deleted_at = utcnow()
    bump_config_version(session)
    session.commit()


def _get_live_checkin(session: Session, checkin_id: int) -> Checkin:
    checkin = session.get(Checkin, checkin_id)
    if checkin is None or checkin.deleted_at is not None:
        raise NotFoundError(f"check-in {checkin_id} not found")
    return checkin


def _apply_checkin(
    session: Session,
    checkin: Checkin,
    name: str,
    time_local: str,
    days_of_week: list[int],
    question_ids: list[int],
    expires_after_minutes: int,
    enabled: bool,
) -> None:
    data = CheckinInput(
        name=name, time_local=time_local, days_of_week=days_of_week,
        question_ids=question_ids, expires_after_minutes=expires_after_minutes, enabled=enabled,
    )
    for qid in data.question_ids:
        _get_live_question(session, qid)

    checkin.name = data.name
    checkin.time_local = data.time_local
    checkin.days_of_week = data.days_of_week
    checkin.expires_after_minutes = data.expires_after_minutes
    checkin.enabled = data.enabled
    checkin.questions.clear()
    session.flush()  # delete old memberships before inserting replacements with the same keys
    checkin.questions.extend(
        CheckinQuestion(question_id=qid, position=i) for i, qid in enumerate(data.question_ids)
    )
