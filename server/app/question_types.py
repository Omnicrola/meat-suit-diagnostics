"""Question types: validation of each type's config and of answer values against that config."""

import re
from decimal import Decimal
from typing import Any, Callable, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

QuestionType = Literal["scale", "boolean", "text", "time", "numeric", "single_select", "multi_select"]

KEY_PATTERN = r"^[a-z][a-z0-9_]{0,31}$"
_TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


class _Config(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ScaleConfig(_Config):
    min: int
    max: int
    step: int = Field(default=1, gt=0)
    min_label: str | None = None
    max_label: str | None = None

    @model_validator(mode="after")
    def _check_range(self):
        if self.max <= self.min:
            raise ValueError("max must be greater than min")
        if (self.max - self.min) % self.step:
            raise ValueError("max - min must be a multiple of step")
        return self


class BooleanConfig(_Config):
    true_label: str = "Yes"
    false_label: str = "No"


class TextConfig(_Config):
    multiline: bool = False
    max_length: int = Field(default=1000, ge=1, le=10000)


class TimeConfig(_Config):
    pass


class NumericField(_Config):
    key: str = Field(pattern=KEY_PATTERN)
    label: str = Field(min_length=1)
    unit: str | None = None
    min: float | None = None
    max: float | None = None
    decimals: int = Field(default=0, ge=0, le=6)

    @model_validator(mode="after")
    def _check_range(self):
        if self.min is not None and self.max is not None and self.max < self.min:
            raise ValueError(f"field {self.key!r}: max must not be less than min")
        return self


class NumericConfig(_Config):
    fields: list[NumericField] = Field(min_length=1)

    @model_validator(mode="after")
    def _unique_keys(self):
        _require_unique([f.key for f in self.fields], "field keys")
        return self


class Option(_Config):
    key: str = Field(pattern=KEY_PATTERN)
    label: str = Field(min_length=1)


class SingleSelectConfig(_Config):
    options: list[Option] = Field(min_length=2)

    @model_validator(mode="after")
    def _unique_keys(self):
        _require_unique([o.key for o in self.options], "option keys")
        return self


class MultiSelectConfig(_Config):
    options: list[Option] = Field(min_length=1)
    min: int = Field(default=0, ge=0)
    max: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _check(self):
        _require_unique([o.key for o in self.options], "option keys")
        upper = len(self.options) if self.max is None else self.max
        if self.min > upper:
            raise ValueError("min must not exceed max or the number of options")
        return self


CONFIG_MODELS: dict[str, type[_Config]] = {
    "scale": ScaleConfig,
    "boolean": BooleanConfig,
    "text": TextConfig,
    "time": TimeConfig,
    "numeric": NumericConfig,
    "single_select": SingleSelectConfig,
    "multi_select": MultiSelectConfig,
}


def validate_config(qtype: str, config: dict[str, Any]) -> dict[str, Any]:
    """Validate a config for the given type; returns it normalized with defaults filled in."""
    model = CONFIG_MODELS.get(qtype)
    if model is None:
        raise ValueError(f"unknown question type {qtype!r}")
    return model.model_validate(config).model_dump()


def validate_value(qtype: str, config: dict[str, Any], value: Any) -> None:
    """Raise ValueError if value is not a valid answer for a question with this type and config."""
    cfg = CONFIG_MODELS[qtype].model_validate(config)
    _VALUE_VALIDATORS[qtype](cfg, value)


def _require_unique(keys: list[str], what: str) -> None:
    if len(set(keys)) != len(keys):
        raise ValueError(f"{what} must be unique")


def _is_number(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _decimal_places(v: int | float) -> int:
    exponent = Decimal(repr(v)).normalize().as_tuple().exponent
    return max(0, -exponent)


def _scale(cfg: ScaleConfig, v: Any) -> None:
    if not isinstance(v, int) or isinstance(v, bool):
        raise ValueError("expected an integer")
    if not cfg.min <= v <= cfg.max:
        raise ValueError(f"must be between {cfg.min} and {cfg.max}")
    if (v - cfg.min) % cfg.step:
        raise ValueError(f"must be in steps of {cfg.step} from {cfg.min}")


def _boolean(cfg: BooleanConfig, v: Any) -> None:
    if not isinstance(v, bool):
        raise ValueError("expected true or false")


def _text(cfg: TextConfig, v: Any) -> None:
    if not isinstance(v, str) or not v.strip():
        raise ValueError("expected non-empty text")
    if len(v) > cfg.max_length:
        raise ValueError(f"must be at most {cfg.max_length} characters")


def _time(cfg: TimeConfig, v: Any) -> None:
    if not isinstance(v, str) or not _TIME_RE.match(v):
        raise ValueError('expected a 24-hour time "HH:MM"')


def _numeric(cfg: NumericConfig, v: Any) -> None:
    if not isinstance(v, dict):
        raise ValueError("expected an object of field values")
    expected = {f.key for f in cfg.fields}
    if set(v) != expected:
        raise ValueError(f"expected exactly the fields {sorted(expected)}")
    for f in cfg.fields:
        n = v[f.key]
        if not _is_number(n):
            raise ValueError(f"{f.key}: expected a number")
        if f.min is not None and n < f.min:
            raise ValueError(f"{f.key}: must be at least {f.min:g}")
        if f.max is not None and n > f.max:
            raise ValueError(f"{f.key}: must be at most {f.max:g}")
        if _decimal_places(n) > f.decimals:
            raise ValueError(f"{f.key}: at most {f.decimals} decimal places")


def _single_select(cfg: SingleSelectConfig, v: Any) -> None:
    if not isinstance(v, str) or v not in {o.key for o in cfg.options}:
        raise ValueError("not one of the options")


def _multi_select(cfg: MultiSelectConfig, v: Any) -> None:
    if not isinstance(v, list) or not all(isinstance(k, str) for k in v):
        raise ValueError("expected a list of option keys")
    if len(set(v)) != len(v):
        raise ValueError("options must not repeat")
    if not set(v) <= {o.key for o in cfg.options}:
        raise ValueError("not all are valid options")
    if len(v) < cfg.min or (cfg.max is not None and len(v) > cfg.max):
        raise ValueError("wrong number of options selected")


_VALUE_VALIDATORS: dict[str, Callable[[Any, Any], None]] = {
    "scale": _scale,
    "boolean": _boolean,
    "text": _text,
    "time": _time,
    "numeric": _numeric,
    "single_select": _single_select,
    "multi_select": _multi_select,
}
