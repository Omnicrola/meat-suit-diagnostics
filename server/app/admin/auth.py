import secrets

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from fastapi import APIRouter, Depends, Request
from fastapi.responses import RedirectResponse

from app.admin.core import flash, render, verify_csrf

router = APIRouter()
_hasher = PasswordHasher()
# Verified against when the username is wrong, so both failure paths take the same time.
_DUMMY_HASH = _hasher.hash(secrets.token_urlsafe(16))


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def _check_credentials(settings, username: str, password: str) -> bool:
    username_ok = secrets.compare_digest(username.encode(), settings.admin_username.encode())
    try:
        password_ok = _hasher.verify(settings.admin_password_hash if username_ok else _DUMMY_HASH, password)
    except (VerificationError, InvalidHashError):
        password_ok = False
    return username_ok and password_ok


@router.get("/login")
def login_form(request: Request):
    if request.session.get("user"):
        return RedirectResponse("/admin", status_code=303)
    return render(request, "login.html", configured=request.app.state.settings.admin_enabled,
                  next=request.query_params.get("next", ""))


@router.post("/login", dependencies=[Depends(verify_csrf)])
async def login(request: Request):
    settings = request.app.state.settings
    limiter = request.app.state.login_limiter
    client = request.client.host if request.client else "unknown"
    if not settings.admin_enabled:
        return render(request, "login.html", status_code=503, configured=False)
    if limiter.is_blocked(client):
        return render(request, "login.html", status_code=429, configured=True,
                      error="Too many failed attempts. Try again in 15 minutes.")

    form = await request.form()
    username = str(form.get("username", ""))
    if not _check_credentials(settings, username, str(form.get("password", ""))):
        limiter.record_failure(client)
        return render(request, "login.html", status_code=401, configured=True,
                      error="Wrong username or password.", username=username, next=form.get("next", ""))

    limiter.reset(client)
    request.session.clear()  # new session (and CSRF token) on login
    request.session["user"] = settings.admin_username
    next_url = str(form.get("next", ""))
    if not next_url.startswith("/admin/"):  # only ever redirect within the admin app
        next_url = "/admin"
    return RedirectResponse(next_url, status_code=303)


@router.post("/logout", dependencies=[Depends(verify_csrf)])
def logout(request: Request):
    request.session.clear()
    flash(request, "Signed out.")
    return RedirectResponse("/admin/login", status_code=303)
