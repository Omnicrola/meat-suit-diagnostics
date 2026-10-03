"""API key management and database backup."""

import os
import sqlite3
import tempfile
from urllib.parse import quote

import qrcode
import qrcode.image.svg
from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, RedirectResponse
from sqlalchemy import func, select
from starlette.background import BackgroundTask

from app.admin.core import AdminUser, flash, render, verify_csrf
from app.db import SessionDep
from app.models import Checkin, Question, ResponseRecord
from app.security import get_active_api_key, issue_api_key, revoke_api_keys
from app.timeutil import utcnow

router = APIRouter()

SETUP_URI_SCHEME = "meatsuit"


def setup_uri(server_url: str, api_key: str) -> str:
    """What the phone's setup QR code encodes."""
    return f"{SETUP_URI_SCHEME}://setup?url={quote(server_url, safe='')}&key={quote(api_key, safe='')}"


def _server_url(request: Request) -> str:
    return request.app.state.settings.public_url or str(request.base_url).rstrip("/")


def _qr_svg(data: str) -> str:
    image = qrcode.make(data, image_factory=qrcode.image.svg.SvgPathFillImage, box_size=10, border=4)
    return image.to_string(encoding="unicode")


@router.get("/api-key")
def api_key_page(request: Request, session: SessionDep, user: AdminUser):
    return render(request, "api_key.html", section="api_key", active=get_active_api_key(session),
                  server_url=_server_url(request))


@router.post("/api-key/generate", dependencies=[Depends(verify_csrf)])
def generate_api_key(request: Request, session: SessionDep, user: AdminUser):
    raw, key = issue_api_key(session)
    server_url = _server_url(request)
    uri = setup_uri(server_url, raw)
    # Rendered directly (not redirected) so the key is never stored anywhere, including the session.
    return render(request, "api_key.html", section="api_key", active=key, server_url=server_url,
                  new_key=raw, qr_svg=_qr_svg(uri))


@router.post("/api-key/revoke", dependencies=[Depends(verify_csrf)])
def revoke_api_key(request: Request, session: SessionDep, user: AdminUser):
    revoke_api_keys(session)
    flash(request, "API key revoked. The phone can no longer sync until you generate a new key.")
    return RedirectResponse("/admin/api-key", status_code=303)


@router.get("/backup")
def backup_page(request: Request, session: SessionDep, user: AdminUser):
    db_path = request.app.state.settings.database_path
    stats = {
        "size_bytes": os.path.getsize(db_path) if os.path.exists(db_path) else 0,
        "questions": session.scalar(select(func.count(func.distinct(Question.id)))),
        "checkins": session.scalar(select(func.count()).select_from(Checkin).where(Checkin.deleted_at.is_(None))),
        "responses": session.scalar(select(func.count()).select_from(ResponseRecord)),
        "latest": session.scalar(select(func.max(ResponseRecord.answered_at))),
    }
    return render(request, "backup.html", section="backup", stats=stats)


@router.get("/backup/download")
def download_backup(request: Request, user: AdminUser):
    """A consistent snapshot via SQLite's online backup API (safe while the app is writing)."""
    fd, tmp_path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    raw = request.app.state.engine.raw_connection()
    try:
        dest = sqlite3.connect(tmp_path)
        try:
            raw.driver_connection.backup(dest)
        finally:
            dest.close()
    finally:
        raw.close()
    return FileResponse(
        tmp_path,
        filename=f"meatsuit-{utcnow():%Y%m%d-%H%M%S}.db",
        media_type="application/vnd.sqlite3",
        background=BackgroundTask(os.remove, tmp_path),
    )
