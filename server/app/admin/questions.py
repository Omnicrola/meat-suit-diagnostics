import json
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy import select

from app import services
from app.admin.core import AdminUser, flash, render, verify_csrf
from app.db import SessionDep
from app.models import Checkin, CheckinQuestion, Question
from app.question_types import CONFIG_MODELS
from app.schemas import describe_error

router = APIRouter(prefix="/questions")

TYPE_LABELS = {
    "scale": "Scale",
    "boolean": "Yes / no",
    "text": "Free text",
    "time": "Clock time",
    "numeric": "Numbers",
    "single_select": "Single choice",
    "multi_select": "Multiple choice",
}
assert TYPE_LABELS.keys() == CONFIG_MODELS.keys()


@router.get("")
def list_questions(request: Request, session: SessionDep, user: AdminUser, deleted: bool = False):
    questions = session.scalars(services.current_questions_query(include_deleted=deleted)).all()
    return render(
        request, "questions/list.html", section="questions", questions=questions,
        show_deleted=deleted, type_labels=TYPE_LABELS, used_in=_checkin_names_by_question(session),
    )


@router.get("/new")
def new_question_form(request: Request, user: AdminUser):
    return _render_form(request, question=None, text="", qtype="scale", config=None)


@router.post("/new", dependencies=[Depends(verify_csrf)])
async def create_question(request: Request, session: SessionDep, user: AdminUser):
    form = await request.form()
    text, qtype = str(form.get("text", "")), str(form.get("type", ""))
    config_json = str(form.get("config_json", ""))
    try:
        question = services.create_question(session, text, qtype, _parse_config(config_json))
    except ValueError as e:
        session.rollback()
        return _render_form(request, None, text, qtype, _try_json(config_json), error=describe_error(e))
    flash(request, f"Created question {question.id}.")
    return RedirectResponse("/admin/questions", status_code=303)


@router.get("/{question_id}")
def edit_question_form(request: Request, question_id: int, session: SessionDep, user: AdminUser):
    question = _get_or_404(session, question_id)
    return _render_form(request, question, question.text, question.type, question.config, session=session)


@router.post("/{question_id}", dependencies=[Depends(verify_csrf)])
async def update_question(request: Request, question_id: int, session: SessionDep, user: AdminUser):
    current = _get_or_404(session, question_id)
    form = await request.form()
    text, config_json = str(form.get("text", "")), str(form.get("config_json", ""))
    try:
        question = services.update_question(session, question_id, text, _parse_config(config_json))
    except services.NotFoundError:
        raise HTTPException(404, "Question not found")
    except ValueError as e:
        session.rollback()
        return _render_form(request, current, text, current.type, _try_json(config_json),
                            error=describe_error(e), session=session)
    if question.version == current.version:
        flash(request, "No changes to save.", "info")
    else:
        flash(request, f"Saved question {question.id} as version {question.version}.")
    return RedirectResponse("/admin/questions", status_code=303)


@router.post("/{question_id}/delete", dependencies=[Depends(verify_csrf)])
def delete_question(request: Request, question_id: int, session: SessionDep, user: AdminUser):
    try:
        services.delete_question(session, question_id)
    except services.NotFoundError:
        raise HTTPException(404, "Question not found")
    flash(request, f"Deleted question {question_id}. Its past answers are kept.")
    return RedirectResponse("/admin/questions", status_code=303)


def _get_or_404(session, question_id: int) -> Question:
    question = services.get_current_question(session, question_id)
    if question is None:
        raise HTTPException(404, "Question not found")
    return question


def _render_form(request, question, text, qtype, config, error=None, session=None):
    versions = []
    used_in: list[str] = []
    if question is not None and session is not None:
        versions = session.scalars(
            select(Question).where(Question.id == question.id).order_by(Question.version.desc())
        ).all()
        used_in = _checkin_names_by_question(session).get(question.id, [])
    return render(
        request, "questions/edit.html", status_code=400 if error else 200, section="questions",
        question=question, text=text, qtype=qtype if qtype in TYPE_LABELS else "scale", config=config,
        type_labels=TYPE_LABELS, versions=versions, used_in=used_in, error=error,
    )


def _parse_config(config_json: str) -> dict[str, Any]:
    config = json.loads(config_json or "{}")
    if not isinstance(config, dict):
        raise ValueError("config must be an object")
    return config


def _try_json(config_json: str) -> Any:
    try:
        return json.loads(config_json)
    except ValueError:
        return None


def _checkin_names_by_question(session) -> dict[int, list[str]]:
    rows = session.execute(
        select(CheckinQuestion.question_id, Checkin.name)
        .join(Checkin, Checkin.id == CheckinQuestion.checkin_id)
        .where(Checkin.deleted_at.is_(None))
        .order_by(Checkin.time_local)
    )
    names: dict[int, list[str]] = {}
    for question_id, name in rows:
        names.setdefault(question_id, []).append(name)
    return names
