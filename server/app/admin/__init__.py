"""Admin web app, served under /admin."""

from urllib.parse import quote

from fastapi import APIRouter, FastAPI, Request
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.admin import auth, checkins, questions, system
from app.admin.core import BASE_DIR, AdminUser, LoginRateLimiter, LoginRequired

_SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
        "frame-ancestors 'none'; form-action 'self'; base-uri 'none'"
    ),
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
}


def install(app: FastAPI) -> None:
    router = APIRouter(prefix="/admin", include_in_schema=False)

    @router.get("")
    def home(user: AdminUser):
        return RedirectResponse("/admin/questions", status_code=303)

    for module in (auth, questions, checkins, system):
        router.include_router(module.router)
    app.include_router(router)
    app.mount("/admin/static", StaticFiles(directory=BASE_DIR / "static"), name="admin-static")
    app.state.login_limiter = LoginRateLimiter()

    @app.exception_handler(LoginRequired)
    def _redirect_to_login(request: Request, exc: LoginRequired):
        return RedirectResponse(f"/admin/login?next={quote(request.url.path)}", status_code=303)

    @app.middleware("http")
    async def _admin_headers(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith("/admin"):
            response.headers.update(_SECURITY_HEADERS)
            # Pages may show secrets (the new API key), so never cache them. Static files revalidate
            # every time (cheap 304s via ETag) so a deploy never leaves stale CSS/JS in the browser.
            static = request.url.path.startswith("/admin/static/")
            response.headers["Cache-Control"] = "no-cache" if static else "no-store"
        return response
