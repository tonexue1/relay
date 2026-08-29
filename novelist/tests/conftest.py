"""测试脚手架。

`Harness` 把 capture/commit 的样板收起来，让每条验收断言只剩它自己要说的事。
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ledger.runtime import MemoryRuntime
from ledger.text import render_episode, render_reflection, render_state
from ledger.types import (
    ClockDomain,
    ClockStamp,
    EpisodeCommand,
    EvidenceRef,
    MemoryBatch,
    MemoryScope,
    MemoryWriterKind,
    OverwritePolicy,
    RawEventDraft,
    ReflectionCommand,
    RenderedText,
    SourceRef,
    SourceType,
    StateCommand,
    StateFieldSpec,
    TargetLifecycle,
    ValueContract,
    WriterPrincipal,
)

WORLD = "world"


@dataclass
class Harness:
    runtime: MemoryRuntime
    space_id: str
    clock: ClockDomain
    run_id: str = "run_1"

    # -- 原文 -----------------------------------------------------------
    def capture(
        self,
        owner: str,
        content: str,
        at: int | None = None,
        *,
        session_id: str = "",
        task_scope_id: str = "",
        idempotency_key: str | None = None,
    ) -> str:
        return self.runtime.capture(
            RawEventDraft(
                space_id=self.space_id,
                owner_id=owner,
                role="user",
                content=content,
                clock_domain=self.clock,
                occurred_at=at,
                session_id=session_id,
                task_scope_id=task_scope_id,
                idempotency_key=idempotency_key,
            )
        )

    # -- 字段目录 --------------------------------------------------------
    def seed(
        self,
        field_id: str,
        contract: ValueContract | None = None,
        *,
        writers: set[MemoryWriterKind] | None = None,
        policy: OverwritePolicy = OverwritePolicy.EXTRACTOR_CAN_CURRENT,
        **kwargs: Any,
    ) -> str:
        spec = StateFieldSpec(
            field_id=field_id,
            contract=contract or ValueContract.text(),
            allowed_writers=frozenset(
                writers
                or {
                    MemoryWriterKind.EXTRACTOR,
                    MemoryWriterKind.USER_EDIT,
                    MemoryWriterKind.HOST_RULE,
                }
            ),
            overwrite_policy=policy,
            **kwargs,
        )
        return self.runtime.ensure_state_field(self.space_id, spec).field_id

    def alias(self, alias: str, canonical: str) -> None:
        self.runtime.put_field_alias(self.space_id, alias, canonical)

    # -- 命令构造 --------------------------------------------------------
    def principal(self, kind: MemoryWriterKind) -> WriterPrincipal:
        return WriterPrincipal(kind=kind, id=f"{kind.value.lower()}_1", policy_version="p1")

    def state(
        self,
        owner: str,
        field_id: str,
        value: Any,
        valid_from: int,
        *,
        source: str,
        writer: MemoryWriterKind = MemoryWriterKind.EXTRACTOR,
        scope: MemoryScope = MemoryScope.PROFILE,
        scope_id: str = "",
        target: TargetLifecycle = TargetLifecycle.CURRENT,
        expected_current_id: str | None = None,
        override_user_edit: bool = False,
        source_type: SourceType = SourceType.RAW_EVENT,
        tags: tuple[str, ...] = (),
        **kwargs: Any,
    ) -> StateCommand:
        payload = value if isinstance(value, dict) else {"value": value}
        text, renderer, version = render_state(field_id, payload)
        return StateCommand(
            principal=self.principal(writer),
            owner_id=owner,
            scope=scope,
            scope_id=scope_id,
            sources=(SourceRef(source_type, source),),
            field_id=field_id,
            payload=payload,
            rendered=RenderedText(text, renderer, version),
            valid_from=ClockStamp(self.clock, valid_from),
            target_lifecycle=target,
            expected_current_id=expected_current_id,
            override_user_edit=override_user_edit,
            tags=tags,
            **kwargs,
        )

    def episode(
        self,
        owner: str,
        summary: str,
        occurred_at: int,
        *,
        source: str,
        key: str,
        writer: MemoryWriterKind = MemoryWriterKind.EXTRACTOR,
        scope: MemoryScope = MemoryScope.PROFILE,
        scope_id: str = "",
        tags: tuple[str, ...] = (),
    ) -> EpisodeCommand:
        text, renderer, version = render_episode(summary)
        return EpisodeCommand(
            principal=self.principal(writer),
            owner_id=owner,
            scope=scope,
            scope_id=scope_id,
            sources=(SourceRef(SourceType.RAW_EVENT, source),),
            rendered=RenderedText(text, renderer, version),
            occurred_at=ClockStamp(self.clock, occurred_at),
            idempotency_key=key,
            tags=tags,
        )

    def reflection(
        self,
        owner: str,
        memory_key: str,
        summary: str,
        valid_from: int,
        *,
        source: str,
        evidence: tuple[str, ...] = (),
        target: TargetLifecycle = TargetLifecycle.CURRENT,
        scope: MemoryScope = MemoryScope.PROFILE,
        scope_id: str = "",
    ) -> ReflectionCommand:
        text, renderer, version = render_reflection(memory_key, summary)
        return ReflectionCommand(
            principal=self.principal(MemoryWriterKind.REFLECTION_WORKER),
            owner_id=owner,
            scope=scope,
            scope_id=scope_id,
            sources=(SourceRef(SourceType.RAW_EVENT, source),),
            memory_key=memory_key,
            rendered=RenderedText(text, renderer, version),
            valid_from=ClockStamp(self.clock, valid_from),
            evidence=tuple(EvidenceRef(e) for e in evidence),
            target_lifecycle=target,
        )

    # -- 提交 -----------------------------------------------------------
    def commit(self, *commands: Any, run_id: str | None = None):
        return self.runtime.commit(
            MemoryBatch(
                space_id=self.space_id,
                writer_run_id=run_id or self.run_id,
                commands=tuple(commands),
            )
        )

    def at(self, value: int) -> ClockStamp:
        return ClockStamp(self.clock, value)


@pytest.fixture
def novel(tmp_path: Path) -> Harness:
    """小说仓：业务时间是章号，STORY_TIME。"""
    runtime = MemoryRuntime(tmp_path / "novel.db")
    runtime.ensure_space("novel:linwan", ClockDomain.STORY_TIME)
    harness = Harness(runtime, "novel:linwan", ClockDomain.STORY_TIME)
    harness.seed("location")
    harness.seed("current_goal")
    harness.seed("status", ValueContract.enum({"健康", "受伤", "失踪", "死亡"}))
    yield harness
    runtime.close()


@pytest.fixture
def assistant(tmp_path: Path) -> Harness:
    """助手仓：业务时间是墙钟。"""
    runtime = MemoryRuntime(tmp_path / "assistant.db")
    runtime.ensure_space("assistant:default", ClockDomain.WALL_CLOCK)
    harness = Harness(runtime, "assistant:default", ClockDomain.WALL_CLOCK)
    yield harness
    runtime.close()
