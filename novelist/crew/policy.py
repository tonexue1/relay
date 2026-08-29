"""宿主策略层：把 Proposal 裁决成 AuthorizedCommand 再落库。

这一层不调 LLM。它是记忆合同里"宿主"的位置：
决定哪条建议能进、以什么身份进、能不能当当前值、新字段怎么占槽。

规则：
* 已种子的字段，抽取器可以升当前值。
* 抽取器提的新字段先占槽（created_by=EXTRACTOR），只能写 CANDIDATE，等作者确认。
* critic 判定过的状态变更以 HOST_RULE 身份写，因为它已经过闸。
* Reflection 必须挂本章刚落地的 Episode 作证据，否则只能 CANDIDATE。
"""

from __future__ import annotations

from ledger.runtime import MemoryRuntime
from ledger.text import render_episode, render_reflection, render_state, sha256
from ledger.types import (
    ClockDomain,
    EpisodeCommand,
    EvidenceRef,
    MemoryBatch,
    MemoryScope,
    MemoryWriterKind,
    OverwritePolicy,
    ProcessingState,
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
    CreatedBy,
)

from . import clock
from .models import Bible, ChapterProposals, StateChange, WORLD_OWNER

POLICY_VERSION = "novelist.host.v1"

#: 已发表的章正文。承接锚点只读这一类——不区分的话，它会读到动作序列，
#: 拿一串"林晚：把针搁回绣绷"去当上一章的语气承接。
ROLE_PROSE = "assistant"
#: 过闸后的动作序列，记忆的事实源。
ROLE_ACTIONS = "action_log"

#: 抽取器"不知道"的时候常写这些词当值。它们不是事实，落库只会污染检索。
_PLACEHOLDERS = frozenset(
    {"无", "没有", "未知", "不明", "不详", "空", "-", "—", "n/a", "na", "null", "none"}
)


def _is_placeholder(value) -> bool:
    return isinstance(value, str) and value.strip().lower() in _PLACEHOLDERS


class HostPolicy:
    def __init__(self, runtime: MemoryRuntime, bible: Bible) -> None:
        self._runtime = runtime
        self._bible = bible
        self._seeded = {seed.field_id for seed in bible.state_fields}
        self._list_fields = {
            seed.field_id for seed in bible.state_fields if seed.kind == "TEXT_LIST"
        }
        self._enum_values = {
            seed.field_id: seed.allowed_values
            for seed in bible.state_fields
            if seed.kind == "ENUM" and seed.allowed_values
        }
        self._extractor_locked = {
            seed.field_id for seed in bible.state_fields if seed.extractor_locked
        }

    def _coerce(self, field_id: str, value):
        """把提议的值归一到字段契约。

        账本对类型和闭集是硬要求，归一是宿主的活。两种归一都是无歧义的：
        数组字段收到单个字符串就包成一元数组；枚举收到"洛阳·府衙外·石阶"
        这种过细的答案，若闭集里恰好只有一个词是它的子串，就取那个词。

        恰好一个是关键。多个都匹配说明真有歧义，宁可让账本弹回去。
        """
        if field_id in self._list_fields and isinstance(value, str):
            return [value.strip()] if value.strip() else []

        allowed = self._enum_values.get(field_id)
        if allowed and isinstance(value, str) and value not in allowed:
            matches = [option for option in allowed if option in value]
            if len(matches) == 1:
                return matches[0]
        return value

    # ------------------------------------------------------------------
    # 原文
    # ------------------------------------------------------------------

    def capture_actions(self, chapter: int, scene_index: int, lines: str) -> str:
        """落一场戏过闸后的动作序列。

        这是记忆的事实源，跟已发表正文分开存（role 不同）。刻意不用正文当事实源：
        正文是叙述者对动作序列的一次渲染，它多写出来的东西没经过 L1/L2。以前从
        正文抽记忆，叙述者一加戏、加出来的戏就进了账本，唯一的拦阻是审校——而
        审校静默失效过一整轮。动作序列是闸门验过的，拿它当事实源才对得上账。
        """
        return self._runtime.capture(
            RawEventDraft(
                space_id=self._bible.space_id,
                owner_id=WORLD_OWNER,
                role=ROLE_ACTIONS,
                content=lines,
                clock_domain=ClockDomain.STORY_TIME,
                occurred_at=clock.story_time(
                    chapter, scene_index, clock.OFFSET_IN_SCENE
                ),
                idempotency_key=f"ch{chapter}:s{scene_index}:acts:{sha256(lines)[:16]}",
            )
        )

    def capture_chapter_prose(self, chapter: int, prose: str) -> str:
        """落已发表的章正文。承接锚点只读这一类。"""
        event_id = self._runtime.capture(
            RawEventDraft(
                space_id=self._bible.space_id,
                owner_id=WORLD_OWNER,
                role=ROLE_PROSE,
                content=prose,
                clock_domain=ClockDomain.STORY_TIME,
                occurred_at=clock.chapter_end(chapter),
                idempotency_key=f"ch{chapter}:prose:{sha256(prose)[:16]}",
            )
        )
        # 正文不是抽取源，抽取只读动作序列。所以它落库即终态——留 PENDING 的话，
        # 每一章的正文都会永久堆在待抽取队列里，那个队列就再也说明不了任何问题。
        self._runtime.set_raw_event_state(event_id, ProcessingState.COMMITTED)
        return event_id

    # ------------------------------------------------------------------
    # 场中：critic 判定过的状态变更
    # ------------------------------------------------------------------

    def apply_state_changes(
        self,
        chapter: int,
        scene_index: int,
        changes: tuple[StateChange, ...],
        source_event_id: str,
        run_id: str,
    ) -> tuple[int, frozenset[tuple[str, str]], tuple[str, ...]]:
        """写在场内偏移上，所以本场读到的仍是场前值，下一场才看到新值。

        返回已写掉的 (owner, field)，让章末抽取不要再覆盖一遍。
        """
        if not changes:
            return 0, frozenset(), ()

        valid_from = clock.stamp(chapter, scene_index, clock.OFFSET_IN_SCENE)
        principal = WriterPrincipal(MemoryWriterKind.HOST_RULE, "critic-vetted", POLICY_VERSION)
        commands = []
        rejected: list[str] = []

        # 同一场里同一个 (owner, field) 只保留最后一条，否则会写出零宽区间。
        deduped: dict[tuple[str, str], StateChange] = {}
        for change in changes:
            deduped[(change.owner_id, change.field_id)] = change

        written: list[tuple[str, str]] = []
        for key, change in deduped.items():
            if change.field_id not in self._seeded:
                rejected.append(f"{change.field_id}：未种子字段，场中不新建槽")
                continue
            if _is_placeholder(change.value):
                rejected.append(f"{change.field_id}：值是占位词「{change.value}」，不落库")
                continue
            payload = {"value": self._coerce(change.field_id, change.value)}
            text, renderer, version = render_state(change.field_id, payload)
            commands.append(
                StateCommand(
                    principal=principal,
                    owner_id=change.owner_id,
                    scope=MemoryScope.PROFILE,
                    scope_id="",
                    sources=(SourceRef(SourceType.RAW_EVENT, source_event_id),),
                    field_id=change.field_id,
                    payload=payload,
                    rendered=RenderedText(text, renderer, version),
                    valid_from=valid_from,
                )
            )
            written.append(key)

        if not commands:
            return 0, frozenset(), tuple(rejected)

        from ledger.types import CommitMode

        result = self._runtime.commit(
            MemoryBatch(
                space_id=self._bible.space_id,
                writer_run_id=run_id,
                commands=tuple(commands),
                commit_mode=CommitMode.BEST_EFFORT,
            )
        )
        rejected.extend(f"{f.code.value}: {f.detail}" for f in result.failures)
        succeeded = frozenset(written[w.command_index] for w in result.writes)
        return len(result.writes), succeeded, tuple(rejected)

    # ------------------------------------------------------------------
    # 场末：裁决抽取结果
    # ------------------------------------------------------------------

    def commit_scene(
        self,
        chapter: int,
        scene_index: int,
        proposals: ChapterProposals,
        source_event_id: str,
        run_id: str,
        skip_states: frozenset[tuple[str, str]] = frozenset(),
    ) -> tuple[int, tuple[str, ...]]:
        extractor = WriterPrincipal(MemoryWriterKind.EXTRACTOR, "chronicler", POLICY_VERSION)
        reflector = WriterPrincipal(
            MemoryWriterKind.REFLECTION_WORKER, "chronicler", POLICY_VERSION
        )
        source = (SourceRef(SourceType.RAW_EVENT, source_event_id),)
        happened_at = clock.stamp(chapter, scene_index, clock.OFFSET_IN_SCENE)
        reflected_at = clock.stamp(chapter, scene_index, clock.OFFSET_AFTER_SCENE)
        rejected: list[str] = []

        # --- 第一批：Episode + State ---------------------------------
        commands = []
        episode_owners: list[str] = []

        for proposal in proposals.episodes:
            text, renderer, version = render_episode(proposal.summary)
            commands.append(
                EpisodeCommand(
                    principal=extractor,
                    owner_id=proposal.owner_id,
                    scope=MemoryScope.PROFILE,
                    scope_id="",
                    sources=source,
                    rendered=RenderedText(text, renderer, version),
                    occurred_at=happened_at,
                    # 内容哈希入键：重跑同一场不会产生重复条目。
                    idempotency_key=f"ch{chapter}:s{scene_index}:{sha256(proposal.summary)[:16]}",
                    tags=proposal.tags,
                    salience=proposal.salience,
                )
            )
            episode_owners.append(proposal.owner_id)

        state_start = len(commands)
        for proposal in proposals.states:
            if _is_placeholder(proposal.value):
                rejected.append(f"{proposal.field_id}：值是占位词「{proposal.value}」，不落库")
                continue
            if proposal.field_id in self._extractor_locked:
                # 这个字段的权威是质检。账本也会挡（allowed_writers 里没有
                # EXTRACTOR），这里先挡是为了给一条说得清的理由。
                rejected.append(f"{proposal.field_id}：抽取器无权写，该字段由质检判定")
                continue
            target = self._authorize_field(proposal.field_id)
            if target is None:
                rejected.append(f"{proposal.field_id}：无法占槽，丢弃")
                continue
            field_id, lifecycle = target
            # critic 已经就这个字段做过判定，抽取器不再覆盖。
            if (proposal.owner_id, field_id) in skip_states:
                continue
            payload = {"value": self._coerce(field_id, proposal.value)}
            text, renderer, version = render_state(field_id, payload)
            commands.append(
                StateCommand(
                    principal=extractor,
                    owner_id=proposal.owner_id,
                    scope=MemoryScope.PROFILE,
                    scope_id="",
                    sources=source,
                    field_id=field_id,
                    payload=payload,
                    rendered=RenderedText(text, renderer, version),
                    valid_from=happened_at,
                    target_lifecycle=lifecycle,
                    confidence=proposal.confidence,
                )
            )

        committed = 0
        evidence_by_owner: dict[str, list[str]] = {}

        if commands:
            # BEST_EFFORT：某条 State 被拒不该带走整章的 Episode。
            from ledger.types import CommitMode

            result = self._runtime.commit(
                MemoryBatch(
                    space_id=self._bible.space_id,
                    writer_run_id=run_id,
                    commands=tuple(commands),
                    commit_mode=CommitMode.BEST_EFFORT,
                )
            )
            committed += len(result.writes)
            rejected.extend(f"{f.code.value}: {f.detail}" for f in result.failures)
            for write in result.writes:
                if write.command_index < state_start:
                    owner = episode_owners[write.command_index]
                    evidence_by_owner.setdefault(owner, []).append(write.memory_id)
            for replay in result.no_ops:
                if replay.command_index < state_start:
                    owner = episode_owners[replay.command_index]
                    evidence_by_owner.setdefault(owner, []).append(replay.memory_id)

        # --- 第二批：Reflection（证据必须先落地）----------------------
        reflection_commands = []
        for proposal in proposals.reflections:
            evidence = tuple(
                EvidenceRef(mid) for mid in evidence_by_owner.get(proposal.owner_id, [])
            )
            text, renderer, version = render_reflection(
                proposal.memory_key, proposal.summary
            )
            reflection_commands.append(
                ReflectionCommand(
                    principal=reflector,
                    owner_id=proposal.owner_id,
                    scope=MemoryScope.PROFILE,
                    scope_id="",
                    sources=source,
                    memory_key=proposal.memory_key,
                    rendered=RenderedText(text, renderer, version),
                    valid_from=reflected_at,
                    evidence=evidence,
                    # 无证据只能 CANDIDATE，合同不允许无据升当前值。
                    target_lifecycle=(
                        TargetLifecycle.CURRENT if evidence else TargetLifecycle.CANDIDATE
                    ),
                )
            )

        if reflection_commands:
            from ledger.types import CommitMode

            result = self._runtime.commit(
                MemoryBatch(
                    space_id=self._bible.space_id,
                    writer_run_id=run_id,
                    commands=tuple(reflection_commands),
                    commit_mode=CommitMode.BEST_EFFORT,
                )
            )
            committed += len(result.writes)
            rejected.extend(f"{f.code.value}: {f.detail}" for f in result.failures)

        # 合同：commit 成功或宿主明确整批拒绝才算消费完。
        # 抽取器什么都没吐通常意味着抽取本身出了问题，留 RETRYABLE 让人看见。
        nothing_proposed = not (
            proposals.episodes or proposals.states or proposals.reflections
        )
        self._runtime.set_raw_event_state(
            source_event_id,
            ProcessingState.RETRYABLE_ERROR
            if nothing_proposed
            else ProcessingState.COMMITTED,
        )
        return committed, tuple(rejected)

    # ------------------------------------------------------------------

    def _authorize_field(self, field_id: str) -> tuple[str, TargetLifecycle] | None:
        """已种子的可升当前值；抽取器提的新名先占槽，只能 CANDIDATE。"""
        canonical = self._runtime.resolve_field(self._bible.space_id, field_id)
        if canonical is not None:
            lifecycle = (
                TargetLifecycle.CURRENT
                if canonical in self._seeded
                else TargetLifecycle.CANDIDATE
            )
            return canonical, lifecycle

        registration = self._runtime.ensure_state_field(
            self._bible.space_id,
            StateFieldSpec(
                field_id=field_id,
                contract=ValueContract.text(),
                allowed_writers=frozenset(
                    {MemoryWriterKind.EXTRACTOR, MemoryWriterKind.USER_EDIT}
                ),
                overwrite_policy=OverwritePolicy.EXTRACTOR_CANDIDATE_ONLY,
                created_by=CreatedBy.EXTRACTOR,
            ),
        )
        return registration.field_id, TargetLifecycle.CANDIDATE
