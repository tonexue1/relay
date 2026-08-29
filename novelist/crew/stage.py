"""搭台：把 Bible 落成记忆仓的字段目录和第 0 章初始状态。

Bible 是宿主的设定，不是模型产物，所以这里全部走 HOST_RULE 写入。
"""

from __future__ import annotations

from ledger.runtime import MemoryRuntime
from ledger.text import render_state
from ledger.types import (
    ClockDomain,
    ClockStamp,
    MemoryBatch,
    MemoryScope,
    MemoryWriterKind,
    OverwritePolicy,
    ProcessingState,
    RawEventDraft,
    RenderedText,
    SourceRef,
    SourceType,
    StateCommand,
    StateFieldSpec,
    ValueContract,
    WriterPrincipal,
)

from .models import Bible, FieldSeed, WORLD_OWNER

SETUP_POLICY_VERSION = "bible.v1"


def _contract(seed: FieldSeed) -> ValueContract:
    if seed.kind == "ENUM":
        return ValueContract.enum(set(seed.allowed_values))
    if seed.kind == "ENUM_LIST":
        return ValueContract.enum_list(set(seed.allowed_values), max_items=seed.max_items)
    if seed.kind == "TEXT_LIST":
        return ValueContract.text_list(max_items=seed.max_items)
    if seed.kind == "NUMBER":
        return ValueContract.number()
    return ValueContract.text()


def build_space(runtime: MemoryRuntime, bible: Bible) -> None:
    """幂等：重复调用只补缺的部分。"""
    runtime.ensure_space(bible.space_id, ClockDomain.STORY_TIME)

    for seed in bible.state_fields:
        writers = {MemoryWriterKind.HOST_RULE, MemoryWriterKind.USER_EDIT}
        if not seed.extractor_locked:
            writers.add(MemoryWriterKind.EXTRACTOR)
        runtime.ensure_state_field(
            bible.space_id,
            StateFieldSpec(
                field_id=seed.field_id,
                contract=_contract(seed),
                allowed_writers=frozenset(writers),
                overwrite_policy=(
                    OverwritePolicy.USER_LOCK
                    if seed.user_lock
                    else OverwritePolicy.EXTRACTOR_CAN_CURRENT
                ),
            ),
        )

    seeded_fields = {seed.field_id for seed in bible.state_fields}
    principal = WriterPrincipal(
        MemoryWriterKind.HOST_RULE, "bible", SETUP_POLICY_VERSION
    )

    for spec in bible.characters:
        if not spec.initial_state:
            continue
        source = runtime.capture(
            RawEventDraft(
                space_id=bible.space_id,
                owner_id=spec.owner_id,
                role="system",
                content=f"{spec.owner_id} 的开篇设定：{spec.persona}",
                clock_domain=ClockDomain.STORY_TIME,
                occurred_at=0,
                idempotency_key=f"setup:{spec.owner_id}",
            )
        )
        commands = []
        for field_id, value in spec.initial_state.items():
            if field_id not in seeded_fields:
                raise ValueError(
                    f"{spec.owner_id} 的初始状态用了未声明字段 {field_id}，先加进 state_fields"
                )
            payload = {"value": value}
            text, renderer, version = render_state(field_id, payload)
            commands.append(
                StateCommand(
                    principal=principal,
                    owner_id=spec.owner_id,
                    scope=MemoryScope.PROFILE,
                    scope_id="",
                    sources=(SourceRef(SourceType.RAW_EVENT, source),),
                    field_id=field_id,
                    payload=payload,
                    rendered=RenderedText(text, renderer, version),
                    valid_from=ClockStamp(ClockDomain.STORY_TIME, 0),
                )
            )
        if commands:
            result = runtime.commit(
                MemoryBatch(
                    space_id=bible.space_id,
                    writer_run_id="setup",
                    commands=tuple(commands),
                )
            )
            if not result.ok:
                raise ValueError(f"{spec.owner_id} 初始状态写入失败：{result.failures}")
            # 开篇设定已经全部落成 State，这条原文没有别的可抽了。不置终态的话，
            # 它会永远留在待抽取队列里，那个队列就再也说明不了任何问题。
            runtime.set_raw_event_state(source, ProcessingState.COMMITTED)


def world_owner_exists(bible: Bible) -> bool:
    return any(c.owner_id == WORLD_OWNER for c in bible.characters)
