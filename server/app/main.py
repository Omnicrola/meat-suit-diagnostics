from fastapi import FastAPI
from fastapi.responses import PlainTextResponse
from sqlalchemy.orm import sessionmaker

from app import api
from app.db import make_engine
from app.settings import Settings


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

    app.include_router(api.router)

    @app.get("/healthz", include_in_schema=False, response_class=PlainTextResponse)
    def healthz() -> str:
        # The one unauthenticated route, for Railway's health check. Reveals nothing.
        return "ok"

    return app
