import logging
import secrets

from fastapi import FastAPI
from fastapi.responses import PlainTextResponse
from sqlalchemy.orm import sessionmaker
from starlette.middleware.sessions import SessionMiddleware

from app import admin, api
from app.db import make_engine
from app.settings import Settings

log = logging.getLogger(__name__)

SESSION_MAX_AGE_SECONDS = 12 * 60 * 60


def create_app(settings: Settings | None = None) -> FastAPI:
    """App factory. Run with: uvicorn app.main:create_app --factory"""
    settings = settings or Settings.from_env()

    app = FastAPI(
        title="Meat Suit Diagnostics",
        docs_url="/docs" if settings.enable_docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.enable_docs else None,
    )
    engine = make_engine(settings.database_path)
    app.state.settings = settings
    app.state.engine = engine
    app.state.sessionmaker = sessionmaker(engine, expire_on_commit=False)

    if not settings.admin_enabled:
        log.warning("Admin login is disabled: set ADMIN_USERNAME and ADMIN_PASSWORD_HASH to enable it.")
    session_secret = settings.session_secret
    if not session_secret:
        log.warning("SESSION_SECRET is not set; using a random one, so admin sessions end on restart.")
        session_secret = secrets.token_urlsafe(32)
    app.add_middleware(
        SessionMiddleware,
        secret_key=session_secret,
        session_cookie="msd_admin_session",
        max_age=SESSION_MAX_AGE_SECONDS,
        path="/admin",
        same_site="strict",
        https_only=settings.secure_cookies,
    )

    app.include_router(api.router)
    admin.install(app)

    @app.get("/healthz", include_in_schema=False, response_class=PlainTextResponse)
    def healthz() -> str:
        # The one unauthenticated route, for Railway's health check. Reveals nothing.
        return "ok"

    return app
