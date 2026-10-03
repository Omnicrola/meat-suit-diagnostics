import pytest

from app.question_types import validate_config, validate_value

SCALE = {"min": 1, "max": 10}
NUMERIC = {"fields": [
    {"key": "systolic", "label": "Systolic", "unit": "mmHg", "min": 50, "max": 250},
    {"key": "temp", "label": "Temperature", "unit": "C", "min": 30, "max": 45, "decimals": 1},
]}
SINGLE = {"options": [{"key": "a", "label": "A"}, {"key": "b", "label": "B"}]}
MULTI = {"options": [{"key": "a", "label": "A"}, {"key": "b", "label": "B"}, {"key": "c", "label": "C"}], "max": 2}


@pytest.mark.parametrize("qtype,config,value", [
    ("scale", SCALE, 1),
    ("scale", SCALE, 10),
    ("scale", {"min": 0, "max": 100, "step": 5}, 35),
    ("scale", {"min": -5, "max": 5}, -3),
    ("boolean", {}, False),
    ("text", {}, "Chicken salad"),
    ("time", {}, "00:00"),
    ("time", {}, "23:59"),
    ("numeric", NUMERIC, {"systolic": 120, "temp": 36.6}),
    ("numeric", NUMERIC, {"systolic": 120.0, "temp": 37}),
    ("single_select", SINGLE, "b"),
    ("multi_select", MULTI, []),
    ("multi_select", MULTI, ["a", "c"]),
])
def test_valid_values(qtype, config, value):
    validate_value(qtype, config, value)


@pytest.mark.parametrize("qtype,config,value", [
    ("scale", SCALE, 0),
    ("scale", SCALE, 11),
    ("scale", SCALE, 5.5),
    ("scale", SCALE, 5.0),
    ("scale", SCALE, True),
    ("scale", SCALE, "5"),
    ("scale", {"min": 0, "max": 100, "step": 5}, 33),
    ("boolean", {}, 1),
    ("boolean", {}, "yes"),
    ("text", {}, ""),
    ("text", {}, "   "),
    ("text", {"max_length": 5}, "too long"),
    ("time", {}, "24:00"),
    ("time", {}, "7:30"),
    ("time", {}, "2026-10-03T07:30:00Z"),
    ("numeric", NUMERIC, {"systolic": 120}),
    ("numeric", NUMERIC, {"systolic": 120, "temp": 36.6, "extra": 1}),
    ("numeric", NUMERIC, {"systolic": 120.5, "temp": 36.6}),
    ("numeric", NUMERIC, {"systolic": 120, "temp": 36.65}),
    ("numeric", NUMERIC, {"systolic": 300, "temp": 36.6}),
    ("numeric", NUMERIC, {"systolic": "120", "temp": 36.6}),
    ("numeric", NUMERIC, [120, 36.6]),
    ("single_select", SINGLE, "z"),
    ("single_select", SINGLE, ["a"]),
    ("multi_select", MULTI, ["a", "b", "c"]),
    ("multi_select", MULTI, ["a", "a"]),
    ("multi_select", MULTI, ["z"]),
    ("multi_select", MULTI, "a"),
])
def test_invalid_values(qtype, config, value):
    with pytest.raises(ValueError):
        validate_value(qtype, config, value)


@pytest.mark.parametrize("qtype,config", [
    ("scale", {"min": 5, "max": 5}),
    ("scale", {"min": 1, "max": 10, "step": 4}),
    ("scale", {"min": 1}),
    ("scale", {"min": 1, "max": 10, "colour": "red"}),
    ("numeric", {"fields": []}),
    ("numeric", {"fields": [{"key": "a", "label": "A"}, {"key": "a", "label": "B"}]}),
    ("numeric", {"fields": [{"key": "Bad Key", "label": "A"}]}),
    ("numeric", {"fields": [{"key": "a", "label": "A", "min": 10, "max": 1}]}),
    ("single_select", {"options": [{"key": "a", "label": "A"}]}),
    ("multi_select", {"options": [{"key": "a", "label": "A"}], "min": 2}),
    ("photo", {}),
])
def test_invalid_configs(qtype, config):
    with pytest.raises(ValueError):
        validate_config(qtype, config)


def test_config_defaults_are_filled_in():
    assert validate_config("scale", {"min": 1, "max": 10}) == {
        "min": 1, "max": 10, "step": 1, "min_label": None, "max_label": None,
    }
