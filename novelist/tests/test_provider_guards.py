"""LLM 客户端的护栏。

这些不是假想的边界，是真模型跑出来的：审校连着两次返回空字符串，被当成合法
产出交给上层，上层解析不出、按"审不出、暂当通过"放过——整道审校静默失效。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pytest

from llm.provider import LLMError, OpenAIProvider, Role, _role_max_tokens_from_env
from run import render_trace_report


@dataclass
class _Message:
    content: str | None


@dataclass
class _Choice:
    message: _Message
    finish_reason: str = "stop"


@dataclass
class _Response:
    choices: list


class _FakeCompletions:
    def __init__(self, script: list) -> None:
        self.script = list(script)
        self.seen: list[dict] = []

    def create(self, **kwargs):
        self.seen.append(kwargs)
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        content, reason = item
        return _Response([_Choice(_Message(content), reason)])


def _provider(monkeypatch, script: list, **env) -> tuple[OpenAIProvider, _FakeCompletions]:
    monkeypatch.setenv("NOVELIST_API_KEY", "sk-test")
    monkeypatch.setenv("NOVELIST_MODEL_DEFAULT", "fake-model")
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    provider = OpenAIProvider(max_retries=3, sleep=lambda _: None)
    completions = _FakeCompletions(script)

    class _Chat:
        pass

    chat = _Chat()
    chat.completions = completions
    provider._client.chat = chat  # type: ignore[attr-defined]
    return provider, completions


def test_空产出会重试而不是把空串交给上层(monkeypatch):
    provider, completions = _provider(
        monkeypatch, [("", "length"), ("", "stop"), ("审完了。", "stop")]
    )
    assert provider.complete_text(Role.REVIEWER, "s", "u") == "审完了。"
    assert len(completions.seen) == 3


def test_一直空产出就报错_不静默返回空(monkeypatch):
    """静默返回空串最坑：上层解析不出，按兜底逻辑当成通过，等于这一步没跑。"""
    provider, _ = _provider(monkeypatch, [("", "length")] * 3)
    with pytest.raises(LLMError, match="空产出"):
        provider.complete_text(Role.REVIEWER, "s", "u")


def test_截断原因被记进流水(monkeypatch):
    """finish_reason=length 是"预算给少了"的唯一证据。不记下来就只能看出
    "模型解析不出"，会误诊成模型笨。"""
    provider, _ = _provider(monkeypatch, [("写到一半就断了", "length")])
    provider.complete_text(Role.REVIEWER, "s", "u")
    assert provider.transcript.calls[-1].finish_reason == "length"


def test_按角色给的预算覆盖全局预算(monkeypatch):
    provider, completions = _provider(
        monkeypatch,
        [("好", "stop"), ("好", "stop")],
        NOVELIST_MAX_TOKENS="8192",
        NOVELIST_MAX_TOKENS_REVIEWER="16384",
    )
    provider.complete_text(Role.REVIEWER, "s", "u")
    provider.complete_text(Role.ACTOR, "s", "u")
    assert completions.seen[0]["max_tokens"] == 16384
    assert completions.seen[1]["max_tokens"] == 8192


def test_没配按角色预算时读不出多余的键(monkeypatch):
    for role in (Role.REVIEWER, Role.ACTOR):
        monkeypatch.delenv(f"NOVELIST_MAX_TOKENS_{role}", raising=False)
    assert _role_max_tokens_from_env() == {}


def _call(role: str, ms: int = 1000, response: str = "好", finish: str = "stop") -> dict:
    return {
        "role": role,
        "system": "s",
        "user": "u",
        "want_json": False,
        "response": response,
        "elapsed_ms": ms,
        "finish_reason": finish,
    }


def test_流水报告拎出截断和空产出():
    """这两类是静默失效的信号。不单独拎出来，就只会表现成"模型解析不出"，
    而排查时最先该问的恰恰是"是不是预算给少了"。"""
    report = render_trace_report(
        [
            _call("REVIEWER", finish="length", response="写到一半"),
            _call("REVIEWER", response=""),
            _call("NARRATOR"),
        ]
    )
    assert "1 次被 max_tokens 截断" in report
    assert "1 次返回空产出" in report
    assert "NOVELIST_MAX_TOKENS_<ROLE>" in report


def test_流水报告干净时明说没问题():
    report = render_trace_report([_call("NARRATOR"), _call("REVIEWER")])
    assert "没有截断，没有空产出" in report


def test_流水报告按耗时排角色():
    """要回答的是"谁在吃时间"，所以按总耗时降序，不是按调用次数。"""
    report = render_trace_report(
        [_call("ACTOR", 500)] * 10 + [_call("NARRATOR", 90_000)]
    )
    assert report.index("NARRATOR") < report.index("ACTOR")


def test_空流水给的是怎么办不是报错():
    assert "--trace" in render_trace_report([])


def test_流水报告能按角色筛():
    report = render_trace_report(
        [_call("NARRATOR"), _call("ACTOR")], grep="NARRATOR"
    )
    body = report.split("收尾")[1]
    assert "NARRATOR" in body and "ACTOR" not in body


def test_离线跑不跟真跑共用产出目录():
    """踩过：一次 `--offline --fresh` 把真跑辛苦生成的章节冲成了假模型的占位
    文本，没有任何提示。假模型产出是烟雾测试副产品，真跑产出是成品。"""
    from run import _resolve_paths, build_parser

    real_db, real_out = _resolve_paths(build_parser().parse_args([]))
    fake_db, fake_out = _resolve_paths(build_parser().parse_args(["--offline"]))
    assert real_out != fake_out
    assert real_db != fake_db
    assert fake_out.name == "offline"


def test_显式给了路径就照办哪怕是离线跑():
    """复现问题时需要能指到同一个库。"""
    from run import _resolve_paths, build_parser

    args = build_parser().parse_args(["--offline", "--out", "/tmp/x", "--db", "/tmp/x/n.db"])
    db, out = _resolve_paths(args)
    assert out == Path("/tmp/x") and db == Path("/tmp/x/n.db")


def test_库默认落在产出目录里():
    from run import _resolve_paths, build_parser

    db, out = _resolve_paths(build_parser().parse_args(["--out", "/tmp/y"]))
    assert db == Path("/tmp/y/novel.db")


def test_审校默认关掉原生思考(monkeypatch):
    """审校的 prompt 已经要求把审查过程写成正文，那就是它的推理。
    再开原生思考等于付两遍钱，还跟正文抢同一个输出预算——真模型上就是这么
    把审查清单写到一半截断的。"""
    provider, completions = _provider(
        monkeypatch,
        [("好", "stop")],
        NOVELIST_THINKING_DISABLED_ROLES="ACTOR,CRITIC,CHRONICLER,DIRECTOR,REVIEWER,TITLER",
    )
    provider.complete_text(Role.REVIEWER, "s", "u")
    assert completions.seen[0]["extra_body"] == {"thinking": {"type": "disabled"}}
