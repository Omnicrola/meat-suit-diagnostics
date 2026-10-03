"""JSON API used by the phone. Every route requires the X-API-Key header."""

import csv
import io
import json
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response
from pydantic import AwareDatetime, ValidationError
from sqlalchemy import select

from app import services
from app.db import SessionDep
from app.models import Checkin, Question, ResponseRecord
from app.question_types import validate_value
from app.schemas import (
    CheckinOut,
    ConfigOut,
    QuestionOut,
    QuestionVersionOut,
    Rejected,
    ResponseIn,
    ResponseList,
    ResponseOut,
    UploadIn,
    UploadResult,
    describe_error,
)
from app.security import require_api_key
from app.timeutil import format_utc, utcnow

router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_api_key)])


@router.get("/config", response_model=ConfigOut, responses={304: {"description": "Config unchanged"}})
def get_config(request: Request, response: Response, session: SessionDep):
    etag = f'"{services.get_config_version(session)}"'
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})

    questions = session.scalars(services.current_questions_query()).all()
    live_ids = {q.id for q in questions}
    checkins = []
    for checkin in session.scalars(services.active_checkins_query()):
        question_ids = [m.question_id for m in checkin.questions if m.question_id in live_ids]
        if question_ids:
            checkins.append(CheckinOut(
                id=checkin.id, name=checkin.name, time_local=checkin.time_local,
                days_of_week=checkin.days_of_week, expires_after_minutes=checkin.expires_after_minutes,
                question_ids=question_ids,
            ))

    response.headers["ETag"] = etag
    return ConfigOut(
        version=services.get_config_version(session),
        questions=[QuestionOut.model_validate(q) for q in questions],
        checkins=checkins,
    )


@router.get("/questions/{question_id}/versions", response_model=list[QuestionVersionOut])
def get_question_versions(question_id: int, session: SessionDep):
    versions = session.scalars(
        select(Question).where(Question.id == question_id).order_by(Question.version)
    ).all()
    if not versions:
        raise HTTPException(404, f"question {question_id} not found")
    return versions


@router.post("/responses", response_model=UploadResult)
def upload_responses(body: UploadIn, session: SessionDep):
    """Idempotent batch upload. Each response is accepted, recognized as a duplicate, or rejected on its own."""
    result = UploadResult(accepted=[], duplicates=[], rejected=[])
    seen: set[str] = set()
    received_at = utcnow()

    for raw in body.responses:
        client_id = str(raw["id"]) if raw.get("id") is not None else None
        try:
            item = ResponseIn.model_validate(raw)
        except ValidationError as e:
            result.rejected.append(Rejected(id=client_id, error=describe_error(e)))
            continue

        rid = str(item.id)
        if rid in seen or session.get(ResponseRecord, rid) is not None:
            result.duplicates.append(client_id)
            continue

        error = _check_references_and_value(session, item)
        if error:
            result.rejected.append(Rejected(id=client_id, error=error))
            continue

        session.add(ResponseRecord(
            id=rid,
            question_id=item.question_id,
            question_version=item.question_version,
            checkin_id=item.checkin_id,
            instance_id=str(item.instance_id) if item.instance_id else None,
            status=item.status,
            value=item.value,
            scheduled_for=item.scheduled_for,
            answered_at=item.answered_at,
            received_at=received_at,
        ))
        seen.add(rid)
        result.accepted.append(client_id)

    session.commit()
    return result


@router.get(
    "/responses",
    response_model=ResponseList,
    responses={200: {"content": {"text/csv": {}}}},
)
def list_responses(
    session: SessionDep,
    from_: Annotated[AwareDatetime, Query(alias="from", description="Inclusive, on answered_at")],
    to: Annotated[AwareDatetime, Query(description="Exclusive, on answered_at")],
    question_id: int | None = None,
    format: Literal["json", "csv"] = "json",
):
    if to <= from_:
        raise HTTPException(422, "'to' must be after 'from'")

    query = (
        select(ResponseRecord, Question.text)
        .join(Question, (Question.id == ResponseRecord.question_id) & (Question.version == ResponseRecord.question_version))
        .where(ResponseRecord.answered_at >= from_, ResponseRecord.answered_at < to)
        .order_by(ResponseRecord.answered_at, ResponseRecord.id)
    )
    if question_id is not None:
        query = query.where(ResponseRecord.question_id == question_id)
    rows = session.execute(query).all()

    if format == "csv":
        return _to_csv(rows, from_, to)
    return ResponseList(responses=[ResponseOut.model_validate(r) for r, _ in rows])


def _check_references_and_value(session, item: ResponseIn) -> str | None:
    # Old and deleted versions are accepted: the phone may have answered before the question changed.
    question = session.get(Question, (item.question_id, item.question_version))
    if question is None:
        return f"unknown question {item.question_id} version {item.question_version}"
    if item.checkin_id is not None and session.get(Checkin, item.checkin_id) is None:
        return f"unknown check-in {item.checkin_id}"
    if item.status == "answered":
        try:
            validate_value(question.type, question.config, item.value)
        except ValueError as e:
            return f"value: {e}"
    return None



_CSV_COLUMNS = [
    "id", "answered_at", "scheduled_for", "received_at", "question_id", "question_version",
    "question_text", "checkin_id", "instance_id", "status", "value",
]


def _csv_value(value) -> str:
    """Plain text for simple answers so they read naturally in a spreadsheet; JSON for structured ones."""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value)  # numbers, true/false, numeric-field objects, multi-select lists


def _to_csv(rows, from_: datetime, to: datetime) -> Response:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(_CSV_COLUMNS)
    for r, question_text in rows:
        writer.writerow([
            r.id,
            format_utc(r.answered_at),
            format_utc(r.scheduled_for) if r.scheduled_for else "",
            format_utc(r.received_at),
            r.question_id,
            r.question_version,
            question_text,
            r.checkin_id if r.checkin_id is not None else "",
            r.instance_id or "",
            r.status,
            _csv_value(r.value),
        ])
    filename = f"responses-{from_:%Y%m%d}-{to:%Y%m%d}.csv"
    return Response(
        buffer.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
