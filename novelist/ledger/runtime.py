"""MemoryRuntime：ledger 合同的 Python 实现。

关键不变量（与 relay/memory/docs 一一对应）：

* 点时读取用 ``valid_from``/``valid_to`` + ``at``，**不用** ``is_current=1``；
  ``is_current`` 只配合唯一索引做写入 CAS。
* 四路检索的硬过滤写在 SQL ``LIMIT`` 之前，禁止先取 Top-K 再过滤。
* 跨 ``space_id`` 恒空；跨 ``owner_id`` 默认恒空，只有 ``include_owners`` 列出的才进来。
* commit 校验顺序固定，见 ``_apply_state`` 里的编号注释。
"""

from __future__ import annotations

import array
import math
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Any, Sequence

from . import text as textutil
from .types import (
    OVERRIDE_CAPABLE,
    SCOPE_PRECEDENCE,
    AuthorityMode,
    BlockedReason,
    ClockDomain,
    ClockStamp,
    CommandFailure,
    CommitErrorCode,
    CommitMode,
    CommitResult,
    CommittedWrite,
    Degradation,
    EpisodeCommand,
    FieldRegistration,
    IndexHealth,
    Lifecycle,
    MemoryBatch,
    MemoryCommand,
    MemoryKind,
    MemoryScope,
    MemoryWriterKind,
    OnMissing,
    OverwritePolicy,
    ProcessingState,
    ProjectionMode as _ProjectionMode,
    RawEventDraft,
    RecallChannel,
    RecallRequest,
    RecallResult,
    ReflectionCommand,
    RequiredStateStatus,
    RetractCommand,
    RiskTier,
    SelectedMemory,
    SourceType,
    StateCommand,
    StateFieldSpec,
    StateHistoryRequest,
    StateItem,
    StateOp,
    StateReadRequest,
    StateReadResult,
    StateSelector,
    StateVersion,
    StateIssue,
    TargetLifecycle,
    ValueContract,
)

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text(encoding="utf-8")


class LedgerError(Exception):
    """调用方用错 API（未知 space、非法别名等），不是逐条命令的业务拒绝。"""


class _Rejected(Exception):
    def __init__(self, code: CommitErrorCode, detail: str) -> None:
        super().__init__(f"{code.value}: {detail}")
        self.code = code
        self.detail = detail


class _Replayed(Exception):
    def __init__(self, write: CommittedWrite) -> None:
        super().__init__("idempotent replay")
        self.write = write


def _now_ms() -> int:
    return int(time.time() * 1000)


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


def _canonical_json(payload: dict[str, Any]) -> str:
    import json

    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _loads(raw: str) -> dict[str, Any]:
    import json

    return json.loads(raw) if raw else {}


class MemoryRuntime:
    def __init__(self, db_path: str | Path = ":memory:") -> None:
        self._conn = sqlite3.connect(str(db_path))
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    # ------------------------------------------------------------------
    # Space 与字段目录
    # ------------------------------------------------------------------

    def ensure_space(self, space_id: str, clock_domain: ClockDomain) -> None:
        row = self._conn.execute(
            "SELECT clock_domain FROM memory_space WHERE id = ?", (space_id,)
        ).fetchone()
        if row is None:
            self._conn.execute(
                "INSERT INTO memory_space (id, clock_domain, created_at) VALUES (?, ?, ?)",
                (space_id, clock_domain.value, _now_ms()),
            )
            self._conn.commit()
            return
        if row["clock_domain"] != clock_domain.value:
            raise LedgerError(
                f"space {space_id} 已是 {row['clock_domain']}，不能改成 {clock_domain.value}"
            )

    def space_clock(self, space_id: str) -> ClockDomain:
        row = self._conn.execute(
            "SELECT clock_domain FROM memory_space WHERE id = ?", (space_id,)
        ).fetchone()
        if row is None:
            raise LedgerError(f"未知 space {space_id}，先调 ensure_space")
        return ClockDomain(row["clock_domain"])

    def ensure_state_field(self, space_id: str, spec: StateFieldSpec) -> FieldRegistration:
        """已有则复用，没有则占槽。入参命中别名时按规范名复用，不建第二个槽。"""
        self.space_clock(space_id)

        alias_target = self._alias_target(space_id, spec.field_id)
        if alias_target is not None and alias_target != spec.field_id:
            return FieldRegistration(alias_target, created=False, reused_via_alias=True)

        existing = self._field_row(space_id, spec.field_id)
        if existing is not None:
            return FieldRegistration(spec.field_id, created=False)

        import json

        self._conn.execute(
            """INSERT INTO state_field (space_id, field_id, created_by, value_contract,
                   allowed_writers, risk_tier, authority_mode, projection_mode,
                   overwrite_policy, created_at, deprecated)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (
                space_id,
                spec.field_id,
                spec.created_by.value,
                spec.contract.to_json(),
                json.dumps(sorted(w.value for w in spec.allowed_writers)),
                spec.risk_tier.value,
                spec.authority_mode.value,
                spec.projection_mode.value,
                spec.overwrite_policy.value,
                _now_ms(),
                int(spec.deprecated),
            ),
        )
        self._conn.commit()
        return FieldRegistration(spec.field_id, created=True)

    def put_field_alias(self, space_id: str, alias: str, canonical_field_id: str) -> None:
        """canonical 必须已占槽；alias 不得占用另一个 field_id。不合并两条值。"""
        if self._field_row(space_id, canonical_field_id) is None:
            raise LedgerError(f"canonical {canonical_field_id} 不在 state_field，先 ensure")
        if alias != canonical_field_id and self._field_row(space_id, alias) is not None:
            raise LedgerError(f"alias {alias} 已是独立 field_id，拒挂；两槽并存，读冲突走 AMBIGUOUS_FIELD")
        self._conn.execute(
            """INSERT INTO state_field_alias (space_id, alias, canonical_field_id)
               VALUES (?,?,?)
               ON CONFLICT (space_id, alias) DO UPDATE SET canonical_field_id = excluded.canonical_field_id""",
            (space_id, alias, canonical_field_id),
        )
        self._conn.commit()

    def resolve_field(self, space_id: str, name: str) -> str | None:
        """别名 → 规范名。返回 None 表示目录里点不到。"""
        target = self._alias_target(space_id, name)
        if target is not None and self._field_row(space_id, target) is not None:
            return target
        if self._field_row(space_id, name) is not None:
            return name
        return None

    def _alias_target(self, space_id: str, alias: str) -> str | None:
        row = self._conn.execute(
            "SELECT canonical_field_id FROM state_field_alias WHERE space_id = ? AND alias = ?",
            (space_id, alias),
        ).fetchone()
        return row["canonical_field_id"] if row else None

    def _field_row(self, space_id: str, field_id: str) -> sqlite3.Row | None:
        return self._conn.execute(
            "SELECT * FROM state_field WHERE space_id = ? AND field_id = ?",
            (space_id, field_id),
        ).fetchone()

    # ------------------------------------------------------------------
    # 原文
    # ------------------------------------------------------------------

    def capture(self, draft: RawEventDraft) -> str:
        space_clock = self.space_clock(draft.space_id)
        if draft.clock_domain is not space_clock:
            raise LedgerError(
                f"CLOCK_DOMAIN_MISMATCH: space 是 {space_clock.value}，事件是 {draft.clock_domain.value}"
            )
        if space_clock is ClockDomain.STORY_TIME and draft.occurred_at is None:
            raise LedgerError("小说 space 的 raw_event 必须带 occurred_at")

        if draft.idempotency_key:
            existing = self._conn.execute(
                "SELECT id FROM raw_event WHERE space_id = ? AND idempotency_key = ?",
                (draft.space_id, draft.idempotency_key),
            ).fetchone()
            if existing:
                return existing["id"]

        event_id = _new_id("raw")
        self._conn.execute(
            """INSERT INTO raw_event (id, space_id, owner_id, session_id, task_scope_id, role,
                   content, clock_domain, occurred_at, captured_at, processing_state,
                   content_hash, idempotency_key, metadata_json)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                event_id,
                draft.space_id,
                draft.owner_id,
                draft.session_id,
                draft.task_scope_id,
                draft.role,
                draft.content,
                draft.clock_domain.value,
                draft.occurred_at,
                _now_ms(),
                ProcessingState.PENDING.value,
                textutil.sha256(draft.content),
                draft.idempotency_key,
                _canonical_json(draft.metadata),
            ),
        )
        self._conn.commit()
        return event_id

    def set_raw_event_state(self, event_id: str, state: ProcessingState) -> None:
        """只有 commit 成功或宿主整批拒绝才可置 COMMITTED。空抽取不自动消费。"""
        self._conn.execute(
            "UPDATE raw_event SET processing_state = ? WHERE id = ?",
            (state.value, event_id),
        )
        self._conn.commit()

    def raw_event_state(self, event_id: str) -> ProcessingState:
        row = self._conn.execute(
            "SELECT processing_state FROM raw_event WHERE id = ?", (event_id,)
        ).fetchone()
        if row is None:
            raise LedgerError(f"未知 raw_event {event_id}")
        return ProcessingState(row["processing_state"])

    def recent_raw_events(
        self,
        space_id: str,
        owner_id: str,
        before: int | None = None,
        limit: int = 5,
        role: str | None = None,
    ) -> list[sqlite3.Row]:
        """按业务时间倒序回读原文。

        检索给的是抽取后的摘要，回读给的是逐字原文。承接上一章语气、复核成文是否
        与前文自洽，都得看原文——摘要丢掉的正是"上一段最后怎么收的"这类信息。

        同一个 owner 下可以存不止一类原文（已发表正文、过闸的动作序列），所以要能
        按 role 筛。不筛就会拿一串"林晚：把针搁回绣绷"去当上一章的语气承接。
        """
        sql = [
            "SELECT * FROM raw_event WHERE space_id = ? AND owner_id = ?",
        ]
        params: list = [space_id, owner_id]
        if role is not None:
            sql.append("AND role = ?")
            params.append(role)
        if before is not None:
            sql.append("AND occurred_at IS NOT NULL AND occurred_at < ?")
            params.append(before)
        sql.append("ORDER BY occurred_at DESC, captured_at DESC LIMIT ?")
        params.append(limit)
        return list(self._conn.execute(" ".join(sql), params))

    def pending_raw_events(self, space_id: str, limit: int = 50) -> list[sqlite3.Row]:
        return list(
            self._conn.execute(
                """SELECT * FROM raw_event
                   WHERE space_id = ? AND processing_state IN ('PENDING', 'RETRYABLE_ERROR')
                   ORDER BY captured_at LIMIT ?""",
                (space_id, limit),
            )
        )

    # ------------------------------------------------------------------
    # 写入
    # ------------------------------------------------------------------

    def commit(self, batch: MemoryBatch) -> CommitResult:
        space_clock = self.space_clock(batch.space_id)
        cur = self._conn.cursor()
        cur.execute("BEGIN")
        writes: list[CommittedWrite] = []
        failures: list[CommandFailure] = []
        no_ops: list[CommittedWrite] = []

        try:
            for index, command in enumerate(batch.commands):
                savepoint = f"cmd_{index}"
                cur.execute(f"SAVEPOINT {savepoint}")
                try:
                    writes.append(self._apply(cur, batch, index, command, space_clock))
                except _Rejected as rejected:
                    cur.execute(f"ROLLBACK TO {savepoint}")
                    failures.append(CommandFailure(index, rejected.code, rejected.detail))
                    if batch.commit_mode is CommitMode.ATOMIC:
                        cur.execute("ROLLBACK")
                        return CommitResult(ok=False, failures=tuple(failures))
                    continue
                except _Replayed as replay:
                    cur.execute(f"ROLLBACK TO {savepoint}")
                    no_ops.append(replay.write)
                    continue
                cur.execute(f"RELEASE {savepoint}")
            cur.execute("COMMIT")
        except Exception:
            cur.execute("ROLLBACK")
            raise

        return CommitResult(
            ok=not failures,
            writes=tuple(writes),
            failures=tuple(failures),
            no_ops=tuple(no_ops),
        )

    def _apply(
        self,
        cur: sqlite3.Cursor,
        batch: MemoryBatch,
        index: int,
        command: MemoryCommand,
        space_clock: ClockDomain,
    ) -> CommittedWrite:
        if isinstance(command, StateCommand):
            return self._apply_state(cur, batch, index, command, space_clock)
        if isinstance(command, EpisodeCommand):
            return self._apply_episode(cur, batch, index, command, space_clock)
        if isinstance(command, ReflectionCommand):
            return self._apply_reflection(cur, batch, index, command, space_clock)
        if isinstance(command, RetractCommand):
            return self._apply_retract(cur, batch, index, command, space_clock)
        raise LedgerError(f"未知命令类型 {type(command).__name__}")

    def _apply_state(
        self,
        cur: sqlite3.Cursor,
        batch: MemoryBatch,
        index: int,
        cmd: StateCommand,
        space_clock: ClockDomain,
    ) -> CommittedWrite:
        # 1. 时钟域
        if cmd.valid_from.domain is not space_clock:
            raise _Rejected(
                CommitErrorCode.CLOCK_DOMAIN_MISMATCH,
                f"space 是 {space_clock.value}，validFrom 是 {cmd.valid_from.domain.value}",
            )
        # 2. 业务时间必填
        if cmd.valid_from.value is None:
            raise _Rejected(CommitErrorCode.MISSING_VALID_FROM, cmd.field_id)

        # 3. 别名 → 规范名；4. 目录必须有
        canonical = self.resolve_field(batch.space_id, cmd.field_id)
        if canonical is None:
            raise _Rejected(
                CommitErrorCode.UNKNOWN_FIELD,
                f"{cmd.field_id} 未占槽，先 ensure_state_field",
            )
        spec_row = self._field_row(batch.space_id, canonical)
        assert spec_row is not None
        if spec_row["deprecated"]:
            raise _Rejected(CommitErrorCode.FIELD_DEPRECATED, canonical)

        # 5. 形状
        contract = ValueContract.from_json(spec_row["value_contract"])
        shape_error = contract.validate(cmd.payload)
        if shape_error:
            raise _Rejected(CommitErrorCode.SCHEMA_MISMATCH, f"{canonical}：{shape_error}")

        # 6. 高风险字段必须过语义检查
        if RiskTier(spec_row["risk_tier"]) is RiskTier.HIGH:
            check = cmd.semantic_check
            if check is None or not check.passed:
                raise _Rejected(
                    CommitErrorCode.SEMANTIC_CHECK_REQUIRED,
                    f"{canonical} 是 HIGH 风险字段，需带通过的 SemanticCheck",
                )

        # 7. 来源存在
        self._require_sources(batch.space_id, cmd.sources)

        # 8. 写入者在白名单
        import json

        allowed = {MemoryWriterKind(w) for w in json.loads(spec_row["allowed_writers"])}
        if cmd.principal.kind not in allowed:
            raise _Rejected(
                CommitErrorCode.WRITER_NOT_ALLOWED,
                f"{cmd.principal.kind.value} 不在 {canonical} 的 allowed_writers",
            )

        # 9. 权威 / 投影
        authority = AuthorityMode(spec_row["authority_mode"])
        projection = _ProjectionMode(spec_row["projection_mode"])
        if authority is AuthorityMode.HOST_AUTHORITATIVE:
            if projection is _ProjectionMode.NONE:
                raise _Rejected(
                    CommitErrorCode.AUTHORITY_VIOLATION,
                    f"{canonical} 由宿主权威且不投影，Memory 不存该字段",
                )
            if cmd.op is not StateOp.MIRROR or cmd.principal.kind is not MemoryWriterKind.HOST_MIRROR:
                raise _Rejected(
                    CommitErrorCode.AUTHORITY_VIOLATION,
                    f"{canonical} 是宿主镜像字段，只接受 HOST_MIRROR 的 MIRROR 写入",
                )

        wants_current = cmd.target_lifecycle is TargetLifecycle.CURRENT

        # 10. 覆盖策略
        policy = OverwritePolicy(spec_row["overwrite_policy"])
        if wants_current and policy is OverwritePolicy.USER_LOCK:
            locked = cur.execute(
                """SELECT id FROM memory_item
                   WHERE space_id = ? AND owner_id = ? AND kind = 'STATE'
                     AND field_id = ? AND is_current = 1 AND writer_kind = 'USER_EDIT'""",
                (batch.space_id, cmd.owner_id, canonical),
            ).fetchone()
            if locked and not (
                cmd.override_user_edit and cmd.principal.kind in OVERRIDE_CAPABLE
            ):
                raise _Rejected(
                    CommitErrorCode.USER_LOCK,
                    f"{canonical} 在该 owner 下已有 USER_EDIT 当前值，跨 scope 也不得升当前值",
                )
        if (
            wants_current
            and policy is OverwritePolicy.EXTRACTOR_CANDIDATE_ONLY
            and cmd.principal.kind is MemoryWriterKind.EXTRACTOR
        ):
            raise _Rejected(
                CommitErrorCode.WRITER_NOT_ALLOWED,
                f"{canonical} 只允许抽取器写 CANDIDATE",
            )

        current = cur.execute(
            """SELECT * FROM memory_item
               WHERE space_id = ? AND owner_id = ? AND kind = 'STATE'
                 AND scope = ? AND scope_id = ? AND field_id = ? AND is_current = 1""",
            (batch.space_id, cmd.owner_id, cmd.scope.value, cmd.scope_id, canonical),
        ).fetchone()

        # 11. 镜像回退
        if (
            projection is _ProjectionMode.MEMORY_MIRROR
            and current is not None
            and cmd.source_revision is not None
            and current["mirrored_source_revision"] is not None
            and cmd.source_revision <= current["mirrored_source_revision"]
        ):
            raise _Rejected(
                CommitErrorCode.STALE_SOURCE_REVISION,
                f"{canonical} 已镜像到 revision {current['mirrored_source_revision']}",
            )

        # 12. CAS
        if wants_current and cmd.expected_current_id is not None:
            actual = current["id"] if current else None
            if actual != cmd.expected_current_id:
                raise _Rejected(
                    CommitErrorCode.CAS_CONFLICT,
                    f"{canonical} 期望当前值 {cmd.expected_current_id}，实际 {actual}",
                )

        # 13. 写行
        memory_id = _new_id("mem")
        if wants_current and current is not None:
            cur.execute(
                """UPDATE memory_item
                   SET is_current = 0,
                       valid_to = COALESCE(valid_to, ?),
                       updated_at = ?
                   WHERE id = ?""",
                (cmd.valid_from.value, _now_ms(), current["id"]),
            )

        self._insert_item(
            cur,
            memory_id=memory_id,
            space_id=batch.space_id,
            owner_id=cmd.owner_id,
            kind=MemoryKind.STATE,
            field_id=canonical,
            memory_key=None,
            payload=cmd.payload,
            rendered=cmd.rendered,
            scope=cmd.scope,
            scope_id=cmd.scope_id,
            is_current=wants_current,
            lifecycle=Lifecycle.ACTIVE if wants_current else Lifecycle.CANDIDATE,
            confidence=cmd.confidence,
            salience=cmd.salience,
            clock_domain=space_clock,
            occurred_at=None,
            valid_from=cmd.valid_from.value,
            supersedes_id=current["id"] if (wants_current and current) else None,
            principal=cmd.principal,
            writer_run_id=batch.writer_run_id,
            mirrored_source_revision=cmd.source_revision,
            idempotency_key=None,
            sources=cmd.sources,
            tags=cmd.tags,
        )

        return CommittedWrite(
            command_index=index,
            memory_id=memory_id,
            kind=MemoryKind.STATE,
            lifecycle=Lifecycle.ACTIVE if wants_current else Lifecycle.CANDIDATE,
            is_current=wants_current,
            superseded_id=current["id"] if (wants_current and current) else None,
        )

    def _apply_episode(
        self,
        cur: sqlite3.Cursor,
        batch: MemoryBatch,
        index: int,
        cmd: EpisodeCommand,
        space_clock: ClockDomain,
    ) -> CommittedWrite:
        if cmd.occurred_at is None:
            raise _Rejected(
                CommitErrorCode.MISSING_OCCURRED_AT,
                f"Episode {cmd.idempotency_key} 缺发生时间",
            )
        if cmd.occurred_at.domain is not space_clock:
            raise _Rejected(
                CommitErrorCode.CLOCK_DOMAIN_MISMATCH,
                f"space 是 {space_clock.value}，occurredAt 是 {cmd.occurred_at.domain.value}",
            )
        if not cmd.idempotency_key:
            raise _Rejected(CommitErrorCode.IDEMPOTENCY_KEY_MISSING, "Episode 必须带幂等键")

        self._require_sources(batch.space_id, cmd.sources)

        # 幂等键含 owner：两角色写同一事件各得一条。
        existing = cur.execute(
            """SELECT id, lifecycle_state FROM memory_item
               WHERE space_id = ? AND owner_id = ? AND kind = 'EPISODE' AND idempotency_key = ?""",
            (batch.space_id, cmd.owner_id, cmd.idempotency_key),
        ).fetchone()
        if existing:
            raise _Replayed(
                CommittedWrite(
                    command_index=index,
                    memory_id=existing["id"],
                    kind=MemoryKind.EPISODE,
                    lifecycle=Lifecycle(existing["lifecycle_state"]),
                    is_current=False,
                )
            )

        memory_id = _new_id("mem")
        lifecycle = (
            Lifecycle.ACTIVE
            if cmd.target_lifecycle is TargetLifecycle.CURRENT
            else Lifecycle.CANDIDATE
        )
        self._insert_item(
            cur,
            memory_id=memory_id,
            space_id=batch.space_id,
            owner_id=cmd.owner_id,
            kind=MemoryKind.EPISODE,
            field_id=None,
            memory_key=None,
            payload=cmd.payload,
            rendered=cmd.rendered,
            scope=cmd.scope,
            scope_id=cmd.scope_id,
            is_current=False,
            lifecycle=lifecycle,
            confidence=cmd.confidence,
            salience=cmd.salience,
            clock_domain=space_clock,
            occurred_at=cmd.occurred_at.value,
            valid_from=None,
            supersedes_id=None,
            principal=cmd.principal,
            writer_run_id=batch.writer_run_id,
            mirrored_source_revision=None,
            idempotency_key=cmd.idempotency_key,
            sources=cmd.sources,
            tags=cmd.tags,
        )
        return CommittedWrite(index, memory_id, MemoryKind.EPISODE, lifecycle, False)

    def _apply_reflection(
        self,
        cur: sqlite3.Cursor,
        batch: MemoryBatch,
        index: int,
        cmd: ReflectionCommand,
        space_clock: ClockDomain,
    ) -> CommittedWrite:
        if cmd.valid_from.domain is not space_clock:
            raise _Rejected(
                CommitErrorCode.CLOCK_DOMAIN_MISMATCH,
                f"space 是 {space_clock.value}，validFrom 是 {cmd.valid_from.domain.value}",
            )
        if not cmd.memory_key:
            raise _Rejected(CommitErrorCode.UNKNOWN_FIELD, "Reflection 必须带 memory_key")

        self._require_sources(batch.space_id, cmd.sources)

        wants_current = cmd.target_lifecycle is TargetLifecycle.CURRENT

        # Reflection 成为当前值前必须挂证据。
        if wants_current and not cmd.evidence:
            raise _Rejected(
                CommitErrorCode.EVIDENCE_REQUIRED,
                f"{cmd.memory_key} 无证据只能 CANDIDATE",
            )
        for ref in cmd.evidence:
            visible = cur.execute(
                "SELECT 1 FROM memory_item WHERE id = ? AND space_id = ?",
                (ref.memory_id, batch.space_id),
            ).fetchone()
            if visible is None:
                raise _Rejected(
                    CommitErrorCode.EVIDENCE_NOT_VISIBLE,
                    f"证据 {ref.memory_id} 不在本 space",
                )

        current = cur.execute(
            """SELECT * FROM memory_item
               WHERE space_id = ? AND owner_id = ? AND kind = 'REFLECTION'
                 AND scope = ? AND scope_id = ? AND memory_key = ? AND is_current = 1""",
            (batch.space_id, cmd.owner_id, cmd.scope.value, cmd.scope_id, cmd.memory_key),
        ).fetchone()

        if wants_current and cmd.expected_current_id is not None:
            actual = current["id"] if current else None
            if actual != cmd.expected_current_id:
                raise _Rejected(
                    CommitErrorCode.CAS_CONFLICT,
                    f"{cmd.memory_key} 期望 {cmd.expected_current_id}，实际 {actual}",
                )

        memory_id = _new_id("mem")
        if wants_current and current is not None:
            cur.execute(
                """UPDATE memory_item
                   SET is_current = 0, valid_to = COALESCE(valid_to, ?), updated_at = ?
                   WHERE id = ?""",
                (cmd.valid_from.value, _now_ms(), current["id"]),
            )

        self._insert_item(
            cur,
            memory_id=memory_id,
            space_id=batch.space_id,
            owner_id=cmd.owner_id,
            kind=MemoryKind.REFLECTION,
            field_id=None,
            memory_key=cmd.memory_key,
            payload=cmd.payload,
            rendered=cmd.rendered,
            scope=cmd.scope,
            scope_id=cmd.scope_id,
            is_current=wants_current,
            lifecycle=Lifecycle.ACTIVE if wants_current else Lifecycle.CANDIDATE,
            confidence=cmd.confidence,
            salience=cmd.salience,
            clock_domain=space_clock,
            occurred_at=None,
            valid_from=cmd.valid_from.value,
            supersedes_id=current["id"] if (wants_current and current) else None,
            principal=cmd.principal,
            writer_run_id=batch.writer_run_id,
            mirrored_source_revision=None,
            idempotency_key=None,
            sources=cmd.sources,
            tags=cmd.tags,
        )
        for ref in cmd.evidence:
            cur.execute(
                """INSERT OR IGNORE INTO memory_evidence (reflection_id, evidence_id, relation)
                   VALUES (?,?,?)""",
                (memory_id, ref.memory_id, ref.relation.value),
            )

        return CommittedWrite(
            index,
            memory_id,
            MemoryKind.REFLECTION,
            Lifecycle.ACTIVE if wants_current else Lifecycle.CANDIDATE,
            wants_current,
            current["id"] if (wants_current and current) else None,
        )

    def _apply_retract(
        self,
        cur: sqlite3.Cursor,
        batch: MemoryBatch,
        index: int,
        cmd: RetractCommand,
        space_clock: ClockDomain,
    ) -> CommittedWrite:
        row = cur.execute(
            "SELECT * FROM memory_item WHERE id = ? AND space_id = ?",
            (cmd.memory_id, batch.space_id),
        ).fetchone()
        if row is None:
            raise _Rejected(CommitErrorCode.MEMORY_NOT_FOUND, cmd.memory_id)
        if cmd.retracted_at is not None and cmd.retracted_at.domain is not space_clock:
            raise _Rejected(
                CommitErrorCode.CLOCK_DOMAIN_MISMATCH,
                f"retractedAt 是 {cmd.retracted_at.domain.value}",
            )
        stamp = cmd.retracted_at.value if cmd.retracted_at else None
        cur.execute(
            """UPDATE memory_item
               SET lifecycle_state = 'RETRACTED', is_current = 0,
                   retracted_at = ?, valid_to = COALESCE(valid_to, ?), updated_at = ?
               WHERE id = ?""",
            (stamp, stamp, _now_ms(), cmd.memory_id),
        )
        cur.execute("DELETE FROM memory_fts WHERE memory_id = ?", (cmd.memory_id,))
        return CommittedWrite(
            index, cmd.memory_id, MemoryKind(row["kind"]), Lifecycle.RETRACTED, False
        )

    def _require_sources(self, space_id: str, sources: Sequence[Any]) -> None:
        if not sources:
            raise _Rejected(CommitErrorCode.SOURCE_NOT_FOUND, "每条记忆至少挂一条来源")
        for ref in sources:
            if ref.type is SourceType.RAW_EVENT:
                found = self._conn.execute(
                    "SELECT 1 FROM raw_event WHERE id = ? AND space_id = ?",
                    (ref.id, space_id),
                ).fetchone()
                if found is None:
                    raise _Rejected(
                        CommitErrorCode.SOURCE_NOT_FOUND, f"raw_event {ref.id} 不存在"
                    )
            elif not ref.id:
                raise _Rejected(CommitErrorCode.SOURCE_NOT_FOUND, f"{ref.type.value} 缺 id")

    def _insert_item(
        self,
        cur: sqlite3.Cursor,
        *,
        memory_id: str,
        space_id: str,
        owner_id: str,
        kind: MemoryKind,
        field_id: str | None,
        memory_key: str | None,
        payload: dict[str, Any],
        rendered: Any,
        scope: MemoryScope,
        scope_id: str,
        is_current: bool,
        lifecycle: Lifecycle,
        confidence: float,
        salience: float,
        clock_domain: ClockDomain,
        occurred_at: int | None,
        valid_from: int | None,
        supersedes_id: str | None,
        principal: Any,
        writer_run_id: str,
        mirrored_source_revision: int | None,
        idempotency_key: str | None,
        sources: Sequence[Any],
        tags: Sequence[str],
    ) -> None:
        payload_json = _canonical_json(payload)
        now = _now_ms()
        cur.execute(
            """INSERT INTO memory_item (
                   id, space_id, owner_id, kind, field_id, memory_key,
                   payload_json, text, renderer_id, renderer_version,
                   scope, scope_id, is_current, lifecycle_state,
                   confidence, salience, clock_domain, occurred_at, valid_from, valid_to,
                   created_at, updated_at, supersedes_id, retracted_at,
                   writer_kind, writer_id, writer_run_id, policy_version,
                   mirrored_source_revision, payload_hash, text_hash, idempotency_key)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                memory_id,
                space_id,
                owner_id,
                kind.value,
                field_id,
                memory_key,
                payload_json,
                rendered.text,
                rendered.renderer_id,
                rendered.renderer_version,
                scope.value,
                scope_id,
                int(is_current),
                lifecycle.value,
                confidence,
                salience,
                clock_domain.value,
                occurred_at,
                valid_from,
                None,
                now,
                now,
                supersedes_id,
                None,
                principal.kind.value,
                principal.id,
                writer_run_id,
                principal.policy_version,
                mirrored_source_revision,
                textutil.sha256(payload_json),
                textutil.sha256(rendered.text),
                idempotency_key,
            ),
        )
        for ref in sources:
            cur.execute(
                """INSERT OR IGNORE INTO memory_source (memory_id, source_type, source_id)
                   VALUES (?,?,?)""",
                (memory_id, ref.type.value, ref.id),
            )
        for tag in tags:
            cur.execute(
                "INSERT OR IGNORE INTO memory_tag (memory_id, tag) VALUES (?,?)",
                (memory_id, tag),
            )
        # FTS 与正文同事务。
        cur.execute(
            "INSERT INTO memory_fts (memory_id, ngram_text) VALUES (?,?)",
            (memory_id, textutil.index_text(rendered.text)),
        )
        cur.execute(
            """INSERT INTO index_job (id, memory_id, kind, status, updated_at)
               VALUES (?,?,'EMBEDDING','PENDING',?)""",
            (_new_id("job"), memory_id, now),
        )

    # ------------------------------------------------------------------
    # 点时读取
    # ------------------------------------------------------------------

    def get_states(self, request: StateReadRequest) -> StateReadResult:
        self.space_clock(request.space_id)
        request.at.require_domain(self.space_clock(request.space_id))

        present: dict[str, StateItem] = {}
        issues: list[StateIssue] = []

        for selector in request.selectors:
            canonical = self.resolve_field(request.space_id, selector.field_id)
            if canonical is None:
                issues.append(
                    StateIssue(
                        selector.field_id,
                        RequiredStateStatus.UNKNOWN_FIELD,
                        "目录里没有这个字段，也没有指向它的别名",
                    )
                )
                continue

            spec_row = self._field_row(request.space_id, canonical)
            assert spec_row is not None
            if (
                AuthorityMode(spec_row["authority_mode"]) is AuthorityMode.HOST_AUTHORITATIVE
                and _ProjectionMode(spec_row["projection_mode"]) is _ProjectionMode.NONE
            ):
                issues.append(
                    StateIssue(
                        canonical,
                        RequiredStateStatus.NOT_PROJECTED,
                        "宿主权威且不投影，Memory 里不存该字段",
                    )
                )
                continue

            own = self._state_at(
                request.space_id,
                [request.owner_id],
                canonical,
                request.at.value,
                request.session_id,
                request.task_scope_id,
                selector.scope,
            )
            if not own:
                candidate = self._conn.execute(
                    """SELECT 1 FROM memory_item
                       WHERE space_id = ? AND owner_id = ? AND kind = 'STATE'
                         AND field_id = ? AND lifecycle_state = 'CANDIDATE'""",
                    (request.space_id, request.owner_id, canonical),
                ).fetchone()
                issues.append(
                    StateIssue(
                        canonical,
                        RequiredStateStatus.CANDIDATE_ONLY
                        if candidate
                        else RequiredStateStatus.MISSING,
                        f"at={request.at.value} 没有生效的当前值",
                    )
                )
                continue

            chosen = own[0]

            if request.include_owners:
                outer = self._state_at(
                    request.space_id,
                    list(request.include_owners),
                    canonical,
                    request.at.value,
                    request.session_id,
                    request.task_scope_id,
                    selector.scope,
                )
                conflicting = [
                    row for row in outer if row["payload_hash"] != chosen["payload_hash"]
                ]
                if conflicting:
                    issues.append(
                        StateIssue(
                            canonical,
                            RequiredStateStatus.AMBIGUOUS_FIELD,
                            f"owner {conflicting[0]['owner_id']} 在同一时刻有不同取值，不自动合并",
                        )
                    )
                    continue

            present[canonical] = self._to_state_item(chosen)

        return StateReadResult(present=present, issues=tuple(issues))

    def _state_at(
        self,
        space_id: str,
        owners: Sequence[str],
        field_id: str,
        at: int,
        session_id: str,
        task_scope_id: str,
        scope: MemoryScope | None,
    ) -> list[sqlite3.Row]:
        """点时取有效的 ACTIVE 版本。按 SESSION > TASK > PROFILE 排序，窄的在前。"""
        clauses = [
            "space_id = ?",
            "kind = 'STATE'",
            "field_id = ?",
            "lifecycle_state = 'ACTIVE'",
            "valid_from IS NOT NULL",
            "valid_from <= ?",
            "(valid_to IS NULL OR valid_to > ?)",
        ]
        params: list[Any] = [space_id, field_id, at, at]

        clauses.append(f"owner_id IN ({','.join('?' * len(owners))})")
        params.extend(owners)

        scope_sql, scope_params = self._scope_clause(session_id, task_scope_id, scope)
        clauses.append(scope_sql)
        params.extend(scope_params)

        rows = list(
            self._conn.execute(
                f"SELECT * FROM memory_item WHERE {' AND '.join(clauses)}", params
            )
        )
        rows.sort(
            key=lambda r: (
                -SCOPE_PRECEDENCE[MemoryScope(r["scope"])],
                -(r["valid_from"] or 0),
            )
        )
        return rows

    @staticmethod
    def _scope_clause(
        session_id: str,
        task_scope_id: str,
        only: MemoryScope | None,
        prefix: str = "",
    ) -> tuple[str, list[Any]]:
        """SESSION/TASK 必须匹配请求里的 scope_id；没给就整类排除，不允许空串兜底。"""
        scope = f"{prefix}scope"
        scope_id = f"{prefix}scope_id"
        parts: list[str] = []
        params: list[Any] = []
        if only is None or only is MemoryScope.PROFILE:
            parts.append(f"{scope} = 'PROFILE'")
        if (only is None or only is MemoryScope.SESSION) and session_id:
            parts.append(f"({scope} = 'SESSION' AND {scope_id} = ?)")
            params.append(session_id)
        if (only is None or only is MemoryScope.TASK) and task_scope_id:
            parts.append(f"({scope} = 'TASK' AND {scope_id} = ?)")
            params.append(task_scope_id)
        if not parts:
            return "0 = 1", []
        return "(" + " OR ".join(parts) + ")", params

    def get_state_history(self, request: StateHistoryRequest) -> list[StateVersion]:
        canonical = self.resolve_field(request.space_id, request.field_id)
        if canonical is None:
            return []
        clauses = ["space_id = ?", "owner_id = ?", "kind = 'STATE'", "field_id = ?"]
        params: list[Any] = [request.space_id, request.owner_id, canonical]
        if request.scope is not None:
            clauses.append("scope = ?")
            params.append(request.scope.value)
        if not request.include_retracted:
            clauses.append("lifecycle_state <> 'RETRACTED'")
        if request.from_inclusive is not None:
            clauses.append("(valid_to IS NULL OR valid_to > ?)")
            params.append(request.from_inclusive)
        if request.to_exclusive is not None:
            clauses.append("valid_from < ?")
            params.append(request.to_exclusive)

        rows = self._conn.execute(
            f"""SELECT * FROM memory_item WHERE {' AND '.join(clauses)}
                ORDER BY valid_from, created_at""",
            params,
        )
        return [
            StateVersion(
                memory_id=r["id"],
                payload=_loads(r["payload_json"]),
                text=r["text"],
                valid_from=r["valid_from"],
                valid_to=r["valid_to"],
                is_current=bool(r["is_current"]),
                lifecycle=Lifecycle(r["lifecycle_state"]),
                writer_kind=MemoryWriterKind(r["writer_kind"]),
                supersedes_id=r["supersedes_id"],
            )
            for r in rows
        ]

    @staticmethod
    def _to_state_item(row: sqlite3.Row) -> StateItem:
        return StateItem(
            memory_id=row["id"],
            field_id=row["field_id"],
            owner_id=row["owner_id"],
            scope=MemoryScope(row["scope"]),
            scope_id=row["scope_id"],
            payload=_loads(row["payload_json"]),
            text=row["text"],
            valid_from=row["valid_from"],
            valid_to=row["valid_to"],
            writer_kind=MemoryWriterKind(row["writer_kind"]),
            confidence=row["confidence"],
        )

    # ------------------------------------------------------------------
    # 召回
    # ------------------------------------------------------------------

    def recall(self, request: RecallRequest) -> RecallResult:
        space_clock = self.space_clock(request.space_id)
        request.at.require_domain(space_clock)

        if request.required_fields and not (
            request.context_contract_id and request.context_contract_version
        ):
            return RecallResult(
                ready=False,
                blocked_reason=BlockedReason.MISSING_CONTRACT_REF,
                issues=(
                    StateIssue(
                        "*",
                        RequiredStateStatus.SCHEMA_MISMATCH,
                        "传了 requiredFields 必须带 contextContractId/Version",
                    ),
                ),
            )

        # 1) 必带点时直读，不走搜索。
        required_states: dict[str, StateItem] = {}
        blocking: list[StateIssue] = []
        warnings: list[StateIssue] = []

        if request.required_fields:
            read = self.get_states(
                StateReadRequest(
                    space_id=request.space_id,
                    owner_id=request.owner_id,
                    selectors=tuple(
                        StateSelector(rf.field_id) for rf in request.required_fields
                    ),
                    at=request.at,
                    include_owners=request.include_owners,
                    session_id=request.session_id,
                    task_scope_id=request.task_scope_id,
                )
            )
            policy = {rf.field_id: rf.on_missing for rf in request.required_fields}
            resolved = {
                rf.field_id: self.resolve_field(request.space_id, rf.field_id) or rf.field_id
                for rf in request.required_fields
            }
            for requested, canonical in resolved.items():
                item = read.present.get(canonical)
                if item is not None:
                    required_states[canonical] = item
                    continue
                issue = next(
                    (i for i in read.issues if i.field_id in (canonical, requested)),
                    StateIssue(canonical, RequiredStateStatus.MISSING),
                )
                if policy[requested] is OnMissing.BLOCK:
                    blocking.append(issue)
                else:
                    warnings.append(issue)

        if blocking:
            return RecallResult(
                ready=False,
                blocked_reason=BlockedReason.REQUIRED_STATE_UNAVAILABLE,
                required_states=required_states,
                issues=tuple(blocking),
                warnings=tuple(warnings),
            )

        # 2) 四路搜索，硬过滤在 LIMIT 之前。
        owners = [request.owner_id, *request.include_owners]
        hits: dict[str, dict[str, Any]] = {}
        trace: dict[str, Any] = {}

        for channel, rows in (
            (RecallChannel.FTS, self._channel_fts(request, owners)),
            (RecallChannel.RECENT, self._channel_recent(request, owners)),
            (RecallChannel.TAG, self._channel_tag(request, owners)),
            (RecallChannel.VECTOR, self._channel_vector(request, owners)),
        ):
            trace[channel.value] = len(rows)
            for rank, row in enumerate(rows):
                entry = hits.setdefault(
                    row["id"], {"row": row, "channels": [], "best_rank": rank}
                )
                entry["channels"].append(channel)
                entry["best_rank"] = min(entry["best_rank"], rank)

        degradations: list[Degradation] = []
        if not self._vector_available():
            degradations.append(
                Degradation("vector", "无激活的 embedding 模型或向量表为空，其余通道照常")
            )

        required_ids = {item.memory_id for item in required_states.values()}
        ranked = sorted(
            (e for e in hits.values() if e["row"]["id"] not in required_ids),
            key=lambda e: (
                -len(e["channels"]),
                -e["row"]["salience"],
                -(e["row"]["occurred_at"] or e["row"]["valid_from"] or 0),
                e["best_rank"],
            ),
        )

        # 3) 预算：必带不可裁，搜索结果按序填。
        blocks: list[str] = []
        for canonical, item in required_states.items():
            blocks.append(f"[必带] {item.text}")
        used = sum(len(b) + 1 for b in blocks)

        selected: list[SelectedMemory] = []
        for position, entry in enumerate(ranked):
            row = entry["row"]
            line = f"[{row['kind']}] {row['text']}"
            if used + len(line) + 1 > request.budget_chars:
                continue
            used += len(line) + 1
            blocks.append(line)
            selected.append(
                SelectedMemory(
                    memory_id=row["id"],
                    kind=MemoryKind(row["kind"]),
                    owner_id=row["owner_id"],
                    text=row["text"],
                    payload=_loads(row["payload_json"]),
                    business_time=row["occurred_at"] if row["occurred_at"] is not None else row["valid_from"],
                    channels=tuple(entry["channels"]),
                    rank=position,
                    score=float(len(entry["channels"])),
                )
            )

        return RecallResult(
            ready=True,
            context="\n".join(blocks),
            required_states=required_states,
            selected=tuple(selected),
            warnings=tuple(warnings),
            degradations=tuple(degradations),
            trace=trace if request.explain else None,
        )

    def _hard_filter(
        self, request: RecallRequest, owners: Sequence[str], alias: str = "mi"
    ) -> tuple[str, list[Any]]:
        at = request.at.value
        clauses = [f"{alias}.space_id = ?"]
        params: list[Any] = [request.space_id]

        clauses.append(f"{alias}.owner_id IN ({','.join('?' * len(owners))})")
        params.extend(owners)

        clauses.append(f"{alias}.lifecycle_state = 'ACTIVE'")

        kinds = sorted(k.value for k in request.kinds)
        clauses.append(f"{alias}.kind IN ({','.join('?' * len(kinds))})")
        params.extend(kinds)

        scope_sql, scope_params = self._scope_clause(
            request.session_id, request.task_scope_id, None, prefix=f"{alias}."
        )
        clauses.append(scope_sql)
        params.extend(scope_params)

        clauses.append(
            f"(({alias}.kind IN ('STATE','REFLECTION')"
            f"  AND {alias}.valid_from IS NOT NULL AND {alias}.valid_from <= ?"
            f"  AND ({alias}.valid_to IS NULL OR {alias}.valid_to > ?))"
            f" OR ({alias}.kind = 'EPISODE'"
            f"  AND {alias}.occurred_at IS NOT NULL AND {alias}.occurred_at <= ?))"
        )
        params.extend([at, at, at])

        return " AND ".join(clauses), params

    def _channel_fts(self, request: RecallRequest, owners: Sequence[str]) -> list[sqlite3.Row]:
        query = textutil.match_query(
            " ".join([request.query, *(m.content for m in request.recent_messages)])
        )
        if not query:
            return []
        where, params = self._hard_filter(request, owners)
        sql = f"""SELECT mi.*, bm25(memory_fts) AS bm25_score
                  FROM memory_fts
                  JOIN memory_item mi ON mi.id = memory_fts.memory_id
                  WHERE memory_fts MATCH ? AND {where}
                  ORDER BY bm25_score
                  LIMIT ?"""
        return list(
            self._conn.execute(sql, [query, *params, request.limit_per_channel])
        )

    def _channel_recent(self, request: RecallRequest, owners: Sequence[str]) -> list[sqlite3.Row]:
        where, params = self._hard_filter(request, owners)
        sql = f"""SELECT mi.* FROM memory_item mi
                  WHERE {where}
                  ORDER BY COALESCE(mi.occurred_at, mi.valid_from) DESC
                  LIMIT ?"""
        return list(self._conn.execute(sql, [*params, request.limit_per_channel]))

    def _channel_tag(self, request: RecallRequest, owners: Sequence[str]) -> list[sqlite3.Row]:
        if not request.tags:
            return []
        where, params = self._hard_filter(request, owners)
        tags = list(request.tags)
        sql = f"""SELECT mi.* FROM memory_item mi
                  JOIN memory_tag mt ON mt.memory_id = mi.id
                  WHERE mt.tag IN ({','.join('?' * len(tags))}) AND {where}
                  ORDER BY COALESCE(mi.occurred_at, mi.valid_from) DESC
                  LIMIT ?"""
        return list(self._conn.execute(sql, [*tags, *params, request.limit_per_channel]))

    def _channel_vector(self, request: RecallRequest, owners: Sequence[str]) -> list[sqlite3.Row]:
        """先跟 FTS 同一套硬过滤，再对候选算 cosine。禁止先 ANN 再过滤。"""
        model = self._active_model()
        if model is None:
            return []
        probe = self._query_vector
        if probe is None:
            return []
        where, params = self._hard_filter(request, owners)
        sql = f"""SELECT mi.*, me.vector_blob FROM memory_item mi
                  JOIN memory_embedding me ON me.memory_id = mi.id AND me.model_id = ?
                  WHERE {where}"""
        rows = list(self._conn.execute(sql, [model, *params]))
        scored = []
        for row in rows:
            vector = array.array("f")
            vector.frombytes(row["vector_blob"])
            scored.append((_cosine(probe, list(vector)), row))
        scored.sort(key=lambda pair: -pair[0])
        return [row for _, row in scored[: request.limit_per_channel]]

    # ------------------------------------------------------------------
    # 向量（异步注入，失败不回滚正文）
    # ------------------------------------------------------------------

    _query_vector: list[float] | None = None

    def register_embedding_model(
        self, model_id: str, dimensions: int, model_version: str = "1"
    ) -> None:
        self._conn.execute(
            """INSERT OR REPLACE INTO embedding_model
                   (model_id, model_version, dimensions, tokenizer_version, active)
               VALUES (?,?,?,?,1)""",
            (model_id, model_version, dimensions, "1"),
        )
        self._conn.commit()

    def put_embedding(self, memory_id: str, model_id: str, vector: Sequence[float]) -> None:
        row = self._conn.execute(
            "SELECT text_hash FROM memory_item WHERE id = ?", (memory_id,)
        ).fetchone()
        if row is None:
            raise LedgerError(f"未知 memory {memory_id}")
        blob = array.array("f", vector).tobytes()
        self._conn.execute(
            """INSERT OR REPLACE INTO memory_embedding
                   (memory_id, model_id, text_hash, vector_blob, indexed_at)
               VALUES (?,?,?,?,?)""",
            (memory_id, model_id, row["text_hash"], blob, _now_ms()),
        )
        self._conn.execute(
            "UPDATE index_job SET status = 'COMPLETED', updated_at = ? WHERE memory_id = ?",
            (_now_ms(), memory_id),
        )
        self._conn.commit()

    def set_query_vector(self, vector: Sequence[float] | None) -> None:
        """宿主注入本轮查询向量。没有就等于向量通道停用。"""
        self._query_vector = list(vector) if vector is not None else None

    def _active_model(self) -> str | None:
        row = self._conn.execute(
            "SELECT model_id FROM embedding_model WHERE active = 1 LIMIT 1"
        ).fetchone()
        return row["model_id"] if row else None

    def _vector_available(self) -> bool:
        if self._active_model() is None:
            return False
        row = self._conn.execute("SELECT COUNT(*) AS n FROM memory_embedding").fetchone()
        return bool(row["n"])

    def index_health(self, space_id: str) -> IndexHealth:
        fts = self._conn.execute(
            """SELECT COUNT(*) AS n FROM memory_fts
               JOIN memory_item mi ON mi.id = memory_fts.memory_id
               WHERE mi.space_id = ?""",
            (space_id,),
        ).fetchone()["n"]
        emb = self._conn.execute(
            """SELECT COUNT(*) AS n FROM memory_embedding me
               JOIN memory_item mi ON mi.id = me.memory_id
               WHERE mi.space_id = ?""",
            (space_id,),
        ).fetchone()["n"]
        pending = self._conn.execute(
            """SELECT COUNT(*) AS n FROM index_job j
               JOIN memory_item mi ON mi.id = j.memory_id
               WHERE mi.space_id = ? AND j.status = 'PENDING'""",
            (space_id,),
        ).fetchone()["n"]
        failed = self._conn.execute(
            """SELECT COUNT(*) AS n FROM index_job j
               JOIN memory_item mi ON mi.id = j.memory_id
               WHERE mi.space_id = ? AND j.status = 'FAILED'""",
            (space_id,),
        ).fetchone()["n"]
        return IndexHealth(fts, emb, pending, failed, self._vector_available())


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right))
    norm = math.sqrt(sum(a * a for a in left)) * math.sqrt(sum(b * b for b in right))
    return dot / norm if norm else 0.0
