"""Unit tests for the declarative engine: parsing, selectors, conditions,
templating, polling, and the flow executor — all Docker-free."""
from __future__ import annotations

import time

import pytest

from automodpack_autotester.engine import conditions, run_flow, selectors
from automodpack_autotester.engine.registry import verb
from automodpack_autotester.engine.util import ClientExited, await_condition, parse_duration


# ── parse_duration ────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "value,expected",
    [
        ("90s", 90.0),
        ("3m", 180.0),
        ("500ms", 0.5),
        ("2h", 7200.0),
        ("180", 180.0),
        (45, 45.0),
        (1.5, 1.5),
    ],
)
def test_parse_duration_values(value, expected):
    assert parse_duration(value) == expected


def test_parse_duration_default_and_invalid():
    assert parse_duration(None, default=12) == 12.0
    assert parse_duration("garbage", default=7) == 7.0
    assert parse_duration(None) is None


# ── await_condition ───────────────────────────────────────────────────────


def test_await_condition_returns_first_non_none():
    seen = []

    def pred():
        seen.append(1)
        return "done" if len(seen) >= 3 else None

    assert await_condition(pred, timeout=5, poll="1ms") == "done"
    assert len(seen) >= 3


def test_await_condition_reraises_client_exited():
    def pred():
        raise ClientExited("client gone")

    with pytest.raises(ClientExited):
        await_condition(pred, timeout=5, poll="1ms")


def test_await_condition_swallows_transient_then_times_out():
    def pred():
        raise RuntimeError("bridge hiccup")

    start = time.monotonic()
    with pytest.raises(TimeoutError) as e:
        await_condition(pred, timeout=0.2, poll="10ms", msg="never")
    assert "bridge hiccup" in str(e.value)
    assert time.monotonic() - start < 2


# ── selectors ─────────────────────────────────────────────────────────────


GUI = {
    "screenClass": "S",
    "buttons": [
        {"id": 1, "text": "Cancel", "enabled": True, "class": "net.Btn"},
        {"id": 2, "text": "Download file", "enabled": False, "class": "net.Btn"},
        {"id": 3, "text": "Download", "enabled": True, "class": "net.Btn"},
    ],
    "textFields": [{"id": 9, "text": "", "enabled": True, "class": "net.Edit"}],
}


def test_selector_exact_preferred_over_substring():
    el = selectors.find_one(GUI, {"text": "Download"})
    assert el["id"] == 3  # exact "Download", not "Download file"


def test_selector_enabled_filter():
    el = selectors.find_one(GUI, {"text_any": ["download file"], "enabled": True})
    assert el is None  # the only "download file" button is disabled


def test_selector_role_and_class():
    assert selectors.find_one(GUI, {"role": "textfield"})["id"] == 9
    assert selectors.find_one(GUI, {"class": "edit"})["id"] == 9


def test_selector_index_negative():
    btns = selectors.find_all(GUI, {"role": "button"})
    assert len(btns) == 3
    assert selectors.find_one(GUI, {"role": "button", "index": -1})["id"] == 3


def test_selector_no_match():
    assert selectors.find_one(GUI, {"text": "nope"}) is None


# ── templating ────────────────────────────────────────────────────────────


def test_resolve_builtins_and_vars(make_ctx):
    ctx = make_ctx(vars={"who": "world"})
    assert ctx.resolve("${target.id}") == "1.21-fabric"
    assert ctx.resolve("${server.host}:${server.port}") == "srv-container:25565"
    assert ctx.resolve("${modpack}") == "amp-autotest"
    assert ctx.resolve("${marker}") == "config/amp-autotest-marker.json"
    assert ctx.resolve("hello ${who}") == "hello world"


def test_resolve_nested_structures(make_ctx):
    ctx = make_ctx(vars={"x": "1"})
    out = ctx.resolve({"a": ["${x}", "b"], "c": {"d": "${modpack}"}})
    assert out == {"a": ["1", "b"], "c": {"d": "amp-autotest"}}


def test_resolve_unknown_var_raises(make_ctx):
    ctx = make_ctx()
    with pytest.raises(KeyError):
        ctx.resolve("${nope}")


# ── conditions ────────────────────────────────────────────────────────────


def test_condition_screen_and_element(make_ctx):
    ctx = make_ctx()
    assert conditions.evaluate(ctx, {"screen": "DownloadScreen"}, gui=GUI) is False
    assert conditions.evaluate(ctx, {"screen": "S"}, gui=GUI) is True
    assert conditions.evaluate(ctx, {"element": {"text": "Download"}}, gui=GUI) is True
    assert conditions.evaluate(ctx, {"no_element": {"text": "missing"}}, gui=GUI) is True


def test_condition_screen_none(make_ctx):
    ctx = make_ctx()
    assert conditions.evaluate(ctx, {"screen_none": True}, gui={"screenClass": None}) is True
    assert conditions.evaluate(ctx, {"screen_none": True}, gui=GUI) is False


def test_condition_file_and_gone(make_ctx):
    ctx = make_ctx()
    (ctx.game_dir / "here.txt").write_text("x")
    assert conditions.evaluate(ctx, {"file": "here.txt"}) is True
    assert conditions.evaluate(ctx, {"file_gone": "nope.txt"}) is True
    assert conditions.evaluate(ctx, {"file": "nope.txt"}) is False


def test_condition_all_any_not(make_ctx):
    ctx = make_ctx()
    cond = {"all": [{"screen": "S"}, {"not": {"screen": "X"}}]}
    assert conditions.evaluate(ctx, cond, gui=GUI) is True
    assert conditions.evaluate(ctx, {"any": [{"screen": "X"}, {"screen": "S"}]}, gui=GUI) is True


def test_condition_log_captures_variable(make_ctx):
    ctx = make_ctx()
    ctx.logs_provider = lambda which, tail=None: "line\nCertificate fingerprint: AB:CD:EF\nmore"
    cond = {"log": {"container": "server", "matches": r"fingerprint[:\s]+([0-9A-Fa-f:]+)",
                    "capture": {"fp": 1}}}
    assert conditions.evaluate(ctx, cond) is True
    assert ctx.vars["fp"] == "AB:CD:EF"


# ── executor ──────────────────────────────────────────────────────────────


@verb("t_rec")
def _t_rec(ctx, step):
    ctx.vars.setdefault("log", []).append(step.get("tag", "?"))


@verb("t_boom")
def _t_boom(ctx, step):
    raise RuntimeError("kaboom")


def test_executor_macro_and_group_expansion(make_ctx):
    ctx = make_ctx()
    lib = {"greet": [{"do": "t_rec", "tag": "a"}, {"do": "t_rec", "tag": "b"}]}
    scenario = {
        "flow": [
            "greet",
            {"group": True, "steps": [{"do": "t_rec", "tag": "c"}]},
            {"use": "greet"},
        ]
    }
    run_flow(ctx, scenario, lib=lib)
    assert ctx.vars["log"] == ["a", "b", "c", "a", "b"]


def test_executor_when_gate_and_repeat(make_ctx):
    ctx = make_ctx()
    scenario = {
        "flow": [
            {"do": "t_rec", "tag": "x", "repeat": 3},
            {"do": "t_rec", "tag": "skipped", "when": {"file": "absent.txt"}},
        ]
    }
    run_flow(ctx, scenario)
    assert ctx.vars["log"] == ["x", "x", "x"]


def test_executor_when_and_repeat_apply_to_macros_and_groups(make_ctx):
    ctx = make_ctx()
    lib = {"greet": [{"do": "t_rec", "tag": "g"}]}
    scenario = {
        "flow": [
            {"use": "greet", "when": {"file": "absent.txt"}},  # gated out
            {"use": "greet", "repeat": 2},  # macro runs twice
            {"group": True, "steps": [{"do": "t_rec", "tag": "x"}], "repeat": 2},
        ]
    }
    run_flow(ctx, scenario, lib=lib)
    assert ctx.vars["log"] == ["g", "g", "x", "x"]


def test_executor_records_results_and_optional(make_ctx):
    ctx = make_ctx()
    results = run_flow(ctx, {"flow": [{"do": "t_boom", "name": "explode", "optional": True}]})
    assert results[0]["ok"] is False
    assert "kaboom" in results[0]["error"]


def test_executor_propagates_failure_with_partial_results(make_ctx):
    ctx = make_ctx()
    collected: list = []
    with pytest.raises(RuntimeError) as e:
        run_flow(
            ctx,
            {"flow": [{"do": "t_rec", "tag": "ok"}, {"do": "t_boom", "name": "bad"}]},
            results=collected,
        )
    assert "step 'bad' failed" in str(e.value)
    assert [r["name"] for r in collected] == ["t_rec", "bad"]
    assert collected[-1]["ok"] is False


def test_executor_unknown_verb(make_ctx):
    ctx = make_ctx()
    with pytest.raises(ValueError):
        run_flow(ctx, {"flow": [{"do": "does_not_exist"}]})


def test_executor_requires_flow(make_ctx):
    ctx = make_ctx()
    with pytest.raises(ValueError):
        run_flow(ctx, {})


# ── log conditions: matchers, counts, whole-log default, file targeting ────

_LOGS = "\n".join(
    ["Prelaunching AutoModpack", "boom ClassNotFoundException: Workarounds$Reference"]
    + [f"noise line {i}" for i in range(1000)]
    + ["AutoModpack prelaunched! took 42ms"]
)


def test_log_whole_log_default_sees_early_lines(make_ctx):
    """An early line is found even with ~1000 lines of trailing noise (no tail=400 footgun)."""
    ctx = make_ctx()
    ctx.logs_provider = lambda which, tail=None: _LOGS if tail is None else "\n".join(
        _LOGS.splitlines()[-tail:]
    )
    assert conditions.evaluate(ctx, {"log": {"matches": "Prelaunching AutoModpack"}}) is True
    # A small explicit tail would scroll the early line out — proving the default matters.
    assert conditions.evaluate(ctx, {"log": {"tail": 5, "matches": "Prelaunching AutoModpack"}}) is False


def test_log_not_matches_and_matches_all_any(make_ctx):
    ctx = make_ctx()
    ctx.logs_provider = lambda which, tail=None: _LOGS
    assert conditions.evaluate(ctx, {"log": {"not_matches": "NoSuchThing"}}) is True
    assert conditions.evaluate(ctx, {"log": {"not_matches": "ClassNotFoundException"}}) is False
    assert conditions.evaluate(
        ctx, {"log": {"matches_all": ["Prelaunching AutoModpack", "prelaunched"]}}
    ) is True
    assert conditions.evaluate(
        ctx, {"log": {"matches_all": ["Prelaunching AutoModpack", "absent"]}}
    ) is False
    assert conditions.evaluate(ctx, {"log": {"matches_any": ["absent", "prelaunched"]}}) is True


def test_log_count_quantifiers(make_ctx):
    ctx = make_ctx()
    ctx.logs_provider = lambda which, tail=None: "hit\nmiss\nhit\nhit\n"
    assert conditions.evaluate(ctx, {"log": {"matches": "hit", "count": 3}}) is True
    assert conditions.evaluate(ctx, {"log": {"matches": "hit", "count": 2}}) is False
    assert conditions.evaluate(ctx, {"log": {"matches": "hit", "min_count": 3}}) is True
    assert conditions.evaluate(ctx, {"log": {"matches": "hit", "max_count": 2}}) is False


def test_log_requires_a_matcher(make_ctx):
    ctx = make_ctx()
    ctx.logs_provider = lambda which, tail=None: ""
    with pytest.raises(ValueError):
        conditions.evaluate(ctx, {"log": {"container": "client"}})


def test_log_file_target_reads_game_dir_artifact(make_ctx):
    ctx = make_ctx()
    debug = ctx.game_dir / "logs" / "debug.log"
    debug.parent.mkdir(parents=True, exist_ok=True)
    debug.write_text("Mixin: Added class metadata for Workarounds$Reference\n")
    cond = {"log": {"file": "logs/debug.log", "matches": r"Added class metadata for \S+Reference"}}
    assert conditions.evaluate(ctx, cond) is True
    # Missing file is empty, not an error.
    assert conditions.evaluate(ctx, {"log": {"file": "logs/missing.log", "matches": "x"}}) is False


# ── server_host / host transport ──────────────────────────────────────────


def test_server_host_overrides_namespace(make_ctx):
    ctx = make_ctx(server_host="127.0.0.1")
    assert ctx.resolve("${server.host}") == "127.0.0.1"
    # Falls back to the container name when unset (bridge transport).
    assert make_ctx().resolve("${server.host}") == "srv-container"
