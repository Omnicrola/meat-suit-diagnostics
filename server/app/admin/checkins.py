from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from sqlalchemy import select

from app import services
from app.admin.core import DAY_NAMES, AdminUser, flash, render, verify_csrf
from app.db import SessionDep
from app.models import Checkin
from app.schemas import describe_error

router = APIRouter(prefix="/checkins")


@router.get("")
def list_checkins(request: Request, session: SessionDep, user: AdminUser):
    checkins = session.scalars(
        select(Checkin).where(Checkin.deleted_at.is_(None)).order_by(Checkin.time_local, Checkin.id)
    ).all()
    return render(request, "checkins/list.html", section="checkins", checkins=checkins)


@router.get("/new")
def new_checkin_form(request: Request, session: SessionDep, user: AdminUser):
    values = {"name": "", "time_local": "09:00", "days_of_week": [1, 2, 3, 4, 5, 6, 7],
              "expires_after_minutes": 120, "enabled": True, "question_ids": []}
    return _render_form(request, session, None, values)


@router.post("/new", dependencies=[Depends(verify_csrf)])
async def create_checkin(request: Request, session: SessionDep, user: AdminUser):
    values = _read_form(await request.form())
    try:
        checkin = services.create_checkin(session, **values)
    except (ValueError, services.NotFoundError) as e:
        session.rollback()
        return _render_form(request, session, None, values, error=describe_error(e))
    flash(request, f"Created check-in “{checkin.name}”.")
    return RedirectResponse("/admin/checkins", status_code=303)


@router.get("/{checkin_id}")
def edit_checkin_form(request: Request, checkin_id: int, session: SessionDep, user: AdminUser):
    checkin = _get_or_404(session, checkin_id)
    values = {
        "name": checkin.name, "time_local": checkin.time_local, "days_of_week": checkin.days_of_week,
        "expires_after_minutes": checkin.expires_after_minutes, "enabled": checkin.enabled,
        "question_ids": [m.question_id for m in checkin.questions],
    }
    return _render_form(request, session, checkin, values)


@router.post("/{checkin_id}", dependencies=[Depends(verify_csrf)])
async def update_checkin(request: Request, checkin_id: int, session: SessionDep, user: AdminUser):
    checkin = _get_or_404(session, checkin_id)
    values = _read_form(await request.form())
    try:
        services.update_checkin(session, checkin_id, **values)
    except (ValueError, services.NotFoundError) as e:
        session.rollback()
        return _render_form(request, session, checkin, values, error=describe_error(e))
    flash(request, f"Saved check-in “{values['name'].strip()}”.")
    return RedirectResponse("/admin/checkins", status_code=303)


@router.post("/{checkin_id}/delete", dependencies=[Depends(verify_csrf)])
def delete_checkin(request: Request, checkin_id: int, session: SessionDep, user: AdminUser):
    checkin = _get_or_404(session, checkin_id)
    services.delete_checkin(session, checkin_id)
    flash(request, f"Deleted check-in “{checkin.name}”. Its past answers are kept.")
    return RedirectResponse("/admin/checkins", status_code=303)


def _get_or_404(session, checkin_id: int) -> Checkin:
    checkin = session.get(Checkin, checkin_id)
    if checkin is None or checkin.deleted_at is not None:
        raise HTTPException(404, "Check-in not found")
    return checkin


def _ints(values) -> list[int]:
    try:
        return [int(v) for v in values]
    except ValueError:
        return []  # only possible with a hand-crafted request; validation then reports the list as empty


def _read_form(form) -> dict:
    try:
        expires = int(form.get("expires_after_minutes", ""))
    except ValueError:
        expires = 0  # fails validation with a clear message
    return {
        "name": str(form.get("name", "")),
        "time_local": str(form.get("time_local", "")),
        "days_of_week": _ints(form.getlist("days_of_week")),
        "question_ids": _ints(form.getlist("question_ids")),
        "expires_after_minutes": expires,
        "enabled": form.get("enabled") == "on",
    }


def _render_form(request, session, checkin, values, error=None):
    questions = session.scalars(services.current_questions_query()).all()
    by_id = {q.id: q for q in questions}
    selected = [by_id[qid] for qid in values["question_ids"] if qid in by_id]
    return render(
        request, "checkins/edit.html", status_code=400 if error else 200, section="checkins",
        checkin=checkin, values=values, questions=questions, selected=selected,
        day_names=DAY_NAMES, error=error,
    )
