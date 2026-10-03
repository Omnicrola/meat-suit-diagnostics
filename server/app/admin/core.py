"""Shared plumbing for the admin web app: templates, login guard, CSRF, flash messages."""

import secrets
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, HTTPException, Request
from fastapi.templating import Jinja2Templates
from starlette.responses import Response

from app.timeutil import format_utc

BASE_DIR = Path(__file__).parent
templates = Jinja2Templates(directory=BASE_DIR / "templates")
templates.env.filters["utc"] = lambda dt: format_utc(dt)[:16].replace("T", " ") + " UTC" if dt else "—"

DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
templates.env.filters["days"] = lambda days: (
    "Every day" if sorted(days) == [1, 2, 3, 4, 5, 6, 7] else ", ".join(DAY_NAMES[d - 1] for d in sorted(days))
)


class LoginRequired(Exception):
    """Raised by require_admin; turned into a redirect to the login page."""


def require_admin(request: Request) -> str:
    user = request.session.get("user")
    if not user or not request.app.state.settings.admin_enabled:
        raise LoginRequired()
    return user


AdminUser = Annotated[str, Depends(require_admin)]


def csrf_token(request: Request) -> str:
    token = request.session.get("csrf")
    if not token:
        token = request.session["csrf"] = secrets.token_urlsafe(32)
    return token


async def verify_csrf(request: Request) -> None:
    form = await request.form()  # Starlette caches the parsed form, so handlers can read it again
    sent = form.get("csrf_token")
    expected = request.session.get("csrf")
    if not (isinstance(sent, str) and expected and secrets.compare_digest(sent, expected)):
        raise HTTPException(403, "Invalid or missing CSRF token. Reload the page and try again.")


def flash(request: Request, message: str, kind: str = "ok") -> None:
    # Reassign rather than append in place: the session is only re-saved when a key is set.
    request.session["flashes"] = [*request.session.get("flashes", []), {"message": message, "kind": kind}]


def render(request: Request, template: str, status_code: int = 200, **context: Any) -> Response:
    context.setdefault("user", request.session.get("user"))
    context["flashes"] = request.session.pop("flashes", [])
    context["csrf_token"] = csrf_token(request)
    return templates.TemplateResponse(request, template, context, status_code=status_code)


class LoginRateLimiter:
    """Allows max_failures failed logins per client within window_seconds."""

    def __init__(self, max_failures: int = 5, window_seconds: float = 900):
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self._failures: dict[str, deque[float]] = defaultdict(deque)

    def _prune(self, key: str) -> deque[float]:
        attempts = self._failures[key]
        cutoff = time.monotonic() - self.window_seconds
        while attempts and attempts[0] < cutoff:
            attempts.popleft()
        return attempts

    def is_blocked(self, key: str) -> bool:
        return len(self._prune(key)) >= self.max_failures

    def record_failure(self, key: str) -> None:
        self._prune(key).append(time.monotonic())

    def reset(self, key: str) -> None:
        self._failures.pop(key, None)
