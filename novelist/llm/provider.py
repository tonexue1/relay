"""LLM 接入。按角色配模型，JSON 输出走 response_format。

`ScriptedProvider` 让整条流水线能在没有 API key 的情况下端到端跑，
这样"编排逻辑对不对"和"模型写得好不好"是两个可以分开验的问题。
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Protocol


class Role:
    PLANNER = "PLANNER"
    DIRECTOR = "DIRECTOR"
    ACTOR = "ACTOR"
    CRITIC = "CRITIC"
    NARRATOR = "NARRATOR"
    REVIEWER = "REVIEWER"
    CHRONICLER = "CHRONICLER"
    TITLER = "TITLER"


@dataclass
class LLMCall:
    role: str
    system: str
    user: str
    want_json: bool
    response: str
    elapsed_ms: int
    #: 端点给的收尾原因。"length" 就是被 max_tokens 截断了——不记下来的话，
    #: 截断产出只会表现成"解析不出"，看上去像模型笨，其实是预算给少了。
    finish_reason: str = ""


class LLMError(Exception):
    pass


class LLMProvider(Protocol):
    def complete_text(self, role: str, system: str, user: str, *, temperature: float = 0.8) -> str:
        ...

    def complete_json(
        self, role: str, system: str, user: str, *, temperature: float = 0.3
    ) -> dict[str, Any]:
        ...


@dataclass
class Transcript:
    """所有 LLM 调用的流水，出问题时用来看是哪一环在胡说。"""

    calls: list[LLMCall] = field(default_factory=list)

    def record(self, call: LLMCall) -> None:
        self.calls.append(call)

    def by_role(self, role: str) -> list[LLMCall]:
        return [c for c in self.calls if c.role == role]

    def total_ms(self) -> int:
        return sum(c.elapsed_ms for c in self.calls)


class OpenAIProvider:
    """OpenAI 兼容端点。base_url 换掉就能接别家。"""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        models: dict[str, str] | None = None,
        transcript: Transcript | None = None,
        max_retries: int = 3,
        max_tokens: int | None = None,
        thinking_disabled_roles: set[str] | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        try:
            from openai import OpenAI
        except ImportError as exc:  # pragma: no cover - 依赖缺失时的提示路径
            raise LLMError("需要 openai 包：pip install -r requirements.txt") from exc

        key = api_key or os.getenv("NOVELIST_API_KEY")
        if not key:
            raise LLMError("缺 NOVELIST_API_KEY，或改用 ScriptedProvider 离线跑")

        self._client = OpenAI(
            api_key=key, base_url=base_url or os.getenv("NOVELIST_BASE_URL") or None
        )
        self._models = models or _models_from_env()
        self._transcript = transcript or Transcript()
        self._max_retries = max_retries
        self._sleep = sleep
        self._max_tokens = max_tokens or int(os.getenv("NOVELIST_MAX_TOKENS", "8192"))
        self._role_max_tokens = _role_max_tokens_from_env()
        self._no_thinking = (
            thinking_disabled_roles
            if thinking_disabled_roles is not None
            else _thinking_disabled_from_env()
        )

    @property
    def transcript(self) -> Transcript:
        return self._transcript

    def _model_for(self, role: str) -> str:
        model = self._models.get(role) or self._models.get("DEFAULT")
        if not model:
            raise LLMError(
                f"{role} 没配模型：设 NOVELIST_MODEL_{role} 或 NOVELIST_MODEL_DEFAULT"
            )
        return model

    def _call(
        self, role: str, system: str, user: str, temperature: float, want_json: bool
    ) -> str:
        if want_json:
            # DeepSeek 的 JSON 模式要求提示里出现 json 字样，否则可能不生效。
            system = _ensure_json_hint(system, user)

        kwargs: dict[str, Any] = {
            "model": self._model_for(role),
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "max_tokens": self._role_max_tokens.get(role, self._max_tokens),
        }
        if want_json:
            kwargs["response_format"] = {"type": "json_object"}
        if role in self._no_thinking:
            kwargs["extra_body"] = {"thinking": {"type": "disabled"}}

        last_error: Exception | None = None
        for attempt in range(self._max_retries):
            started = time.monotonic()
            try:
                response = self._client.chat.completions.create(**kwargs)
            except Exception as exc:  # 网络/限流：退避重试
                last_error = exc
                self._sleep(1.5 * (attempt + 1))
                continue
            choice = response.choices[0]
            content = choice.message.content or ""
            self._transcript.record(
                LLMCall(
                    role=role,
                    system=system,
                    user=user,
                    want_json=want_json,
                    response=content,
                    elapsed_ms=int((time.monotonic() - started) * 1000),
                    finish_reason=choice.finish_reason or "",
                )
            )
            if content.strip():
                return content
            # 空产出：思考型模型把预算全花在 reasoning 上、正文一个字没吐。
            # 当成失败重试，而不是把空串交给上层——空串到了上层只会表现成
            # "解析不出"，被兜底逻辑当作通过静默放过。
            last_error = LLMError(
                f"{role} 返回空产出（finish_reason={choice.finish_reason}）"
            )
            self._sleep(1.0 * (attempt + 1))
        raise LLMError(f"{role} 调用连续失败 {self._max_retries} 次：{last_error}")

    def complete_text(self, role: str, system: str, user: str, *, temperature: float = 0.8) -> str:
        return self._call(role, system, user, temperature, want_json=False).strip()

    def complete_json(
        self, role: str, system: str, user: str, *, temperature: float = 0.3
    ) -> dict[str, Any]:
        raw = self._call(role, system, user, temperature, want_json=True)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            repaired = _extract_json_object(raw)
            if repaired is None:
                raise LLMError(f"{role} 没有返回可解析的 JSON：{raw[:200]}")
            return repaired


class ScriptedProvider:
    """离线替身。handler 按角色返回结果，用来验编排而不是验文笔。"""

    def __init__(
        self,
        handlers: dict[str, Callable[[str, str], Any]],
        transcript: Transcript | None = None,
    ) -> None:
        self._handlers = handlers
        self._transcript = transcript or Transcript()

    @property
    def transcript(self) -> Transcript:
        return self._transcript

    def override(self, role: str, handler: Callable[[str, str], Any]) -> Callable:
        """换掉某个角色的 handler，返回原来的那个。

        用来构造特定剧情：比如让导演先判一次 CONTINUE，验多轮推进的编排。
        """
        previous = self._handlers.get(role)
        self._handlers[role] = handler
        return previous

    def _dispatch(self, role: str, system: str, user: str, want_json: bool) -> Any:
        handler = self._handlers.get(role)
        if handler is None:
            raise LLMError(f"ScriptedProvider 没有配 {role} 的 handler")
        started = time.monotonic()
        result = handler(system, user)
        self._transcript.record(
            LLMCall(
                role=role,
                system=system,
                user=user,
                want_json=want_json,
                response=json.dumps(result, ensure_ascii=False)
                if not isinstance(result, str)
                else result,
                elapsed_ms=int((time.monotonic() - started) * 1000),
            )
        )
        return result

    def complete_text(self, role: str, system: str, user: str, *, temperature: float = 0.8) -> str:
        return str(self._dispatch(role, system, user, want_json=False))

    def complete_json(
        self, role: str, system: str, user: str, *, temperature: float = 0.3
    ) -> dict[str, Any]:
        result = self._dispatch(role, system, user, want_json=True)
        return result if isinstance(result, dict) else json.loads(result)


def _models_from_env() -> dict[str, str]:
    return {
        Role.PLANNER: os.getenv("NOVELIST_MODEL_PLANNER", ""),
        Role.DIRECTOR: os.getenv("NOVELIST_MODEL_DIRECTOR", ""),
        Role.ACTOR: os.getenv("NOVELIST_MODEL_ACTOR", ""),
        Role.CRITIC: os.getenv("NOVELIST_MODEL_CRITIC", ""),
        Role.NARRATOR: os.getenv("NOVELIST_MODEL_NARRATOR", ""),
        Role.REVIEWER: os.getenv("NOVELIST_MODEL_REVIEWER", ""),
        Role.CHRONICLER: os.getenv("NOVELIST_MODEL_CHRONICLER", ""),
        Role.TITLER: os.getenv("NOVELIST_MODEL_TITLER", ""),
        "DEFAULT": os.getenv("NOVELIST_MODEL_DEFAULT", ""),
    }


def _role_max_tokens_from_env() -> dict[str, int]:
    """按角色给输出预算。

    审校要先写审查过程再吐清单，篇幅是别的角色的几倍；给统一预算它会写到一半
    被截断，清单永远出不来。叙述者同理。
    """
    found = {}
    for role in (
        Role.PLANNER,
        Role.DIRECTOR,
        Role.ACTOR,
        Role.CRITIC,
        Role.NARRATOR,
        Role.REVIEWER,
        Role.CHRONICLER,
        Role.TITLER,
    ):
        raw = os.getenv(f"NOVELIST_MAX_TOKENS_{role}")
        if raw:
            found[role] = int(raw)
    return found


def _thinking_disabled_from_env() -> set[str]:
    raw = os.getenv("NOVELIST_THINKING_DISABLED_ROLES", "")
    return {part.strip().upper() for part in raw.split(",") if part.strip()}


def _ensure_json_hint(system: str, user: str) -> str:
    if "json" in (system + user).casefold():
        return system
    return system + "\n只输出 json。"


def _extract_json_object(raw: str) -> dict[str, Any] | None:
    """模型偶尔会在 JSON 外面裹一层 markdown 或解释。"""
    start = raw.find("{")
    end = raw.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        return json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return None
