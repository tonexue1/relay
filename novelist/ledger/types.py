"""Ledger 合同类型。

对应 relay/memory/docs/api.md 与 schema.md。命名刻意跟 Kotlin 侧保持一致，
这样两套实现跑同一组验收条目时能逐条对上。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


# --------------------------------------------------------------------------
# 时钟
# --------------------------------------------------------------------------


class ClockDomain(str, Enum):
    WALL_CLOCK = "WALL_CLOCK"
    STORY_TIME = "STORY_TIME"


@dataclass(frozen=True)
class ClockStamp:
    domain: ClockDomain
    value: int

    def require_domain(self, expected: ClockDomain) -> None:
        if self.domain is not expected:
            raise ClockDomainMismatch(
                f"expected {expected.value}, got {self.domain.value}"
            )


class ClockDomainMismatch(Exception):
    """跨时钟域比较。不做隐式换算。"""


# --------------------------------------------------------------------------
# 枚举
# --------------------------------------------------------------------------


class MemoryKind(str, Enum):
    STATE = "STATE"
    EPISODE = "EPISODE"
    REFLECTION = "REFLECTION"


class MemoryScope(str, Enum):
    PROFILE = "PROFILE"
    TASK = "TASK"
    SESSION = "SESSION"


#: 必带撞上多条同 field 时的覆盖顺序，窄的赢。
SCOPE_PRECEDENCE: dict[MemoryScope, int] = {
    MemoryScope.SESSION: 3,
    MemoryScope.TASK: 2,
    MemoryScope.PROFILE: 1,
}


class Lifecycle(str, Enum):
    CANDIDATE = "CANDIDATE"
    ACTIVE = "ACTIVE"
    RETRACTED = "RETRACTED"


class MemoryWriterKind(str, Enum):
    USER_EDIT = "USER_EDIT"
    HOST_RULE = "HOST_RULE"
    EXTRACTOR = "EXTRACTOR"
    REFLECTION_WORKER = "REFLECTION_WORKER"
    IMPORTER = "IMPORTER"
    HOST_MIRROR = "HOST_MIRROR"


#: 来源优先级，高的不该被低的静默覆盖。
WRITER_PRIORITY: dict[MemoryWriterKind, int] = {
    MemoryWriterKind.USER_EDIT: 6,
    MemoryWriterKind.HOST_MIRROR: 5,
    MemoryWriterKind.HOST_RULE: 4,
    MemoryWriterKind.IMPORTER: 3,
    MemoryWriterKind.EXTRACTOR: 2,
    MemoryWriterKind.REFLECTION_WORKER: 1,
}

#: 可以强推用户手改的写入者。
OVERRIDE_CAPABLE: frozenset[MemoryWriterKind] = frozenset(
    {MemoryWriterKind.USER_EDIT, MemoryWriterKind.HOST_RULE}
)


class AuthorityMode(str, Enum):
    MEMORY_AUTHORITATIVE = "MEMORY_AUTHORITATIVE"
    HOST_AUTHORITATIVE = "HOST_AUTHORITATIVE"


class ProjectionMode(str, Enum):
    NONE = "NONE"
    MEMORY_MIRROR = "MEMORY_MIRROR"


class RiskTier(str, Enum):
    LOW = "LOW"
    HIGH = "HIGH"


class OverwritePolicy(str, Enum):
    EXTRACTOR_CAN_CURRENT = "EXTRACTOR_CAN_CURRENT"
    EXTRACTOR_CANDIDATE_ONLY = "EXTRACTOR_CANDIDATE_ONLY"
    USER_LOCK = "USER_LOCK"


class CreatedBy(str, Enum):
    HOST_SEED = "HOST_SEED"
    EXTRACTOR = "EXTRACTOR"
    USER_EDIT = "USER_EDIT"


class SourceType(str, Enum):
    RAW_EVENT = "RAW_EVENT"
    USER_EDIT = "USER_EDIT"
    HOST_TXN = "HOST_TXN"
    IMPORT = "IMPORT"


class StateOp(str, Enum):
    PUT = "PUT"
    CONFIRM = "CONFIRM"
    MIRROR = "MIRROR"


class TargetLifecycle(str, Enum):
    CANDIDATE = "CANDIDATE"
    CURRENT = "CURRENT"


class EvidenceRelation(str, Enum):
    SUPPORTS = "SUPPORTS"
    CONTRADICTS = "CONTRADICTS"
    MOTIVATES = "MOTIVATES"


class ProcessingState(str, Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMMITTED = "COMMITTED"
    RETRYABLE_ERROR = "RETRYABLE_ERROR"
    PERMANENT_ERROR = "PERMANENT_ERROR"


class CommitMode(str, Enum):
    ATOMIC = "ATOMIC"
    BEST_EFFORT = "BEST_EFFORT"


# --------------------------------------------------------------------------
# ValueContract：封闭集合，只保证形状
# --------------------------------------------------------------------------


class ContractKind(str, Enum):
    TEXT = "TEXT"
    ENUM = "ENUM"
    NUMBER = "NUMBER"
    BOOL = "BOOL"
    INSTANT = "INSTANT"
    TEXT_LIST = "TEXT_LIST"
    ENUM_LIST = "ENUM_LIST"
    RECORD = "RECORD"
    OPAQUE_JSON = "OPAQUE_JSON"


#: RECORD 的字段值只能是标量契约，不允许嵌套。
_SCALAR_KINDS = frozenset(
    {
        ContractKind.TEXT,
        ContractKind.ENUM,
        ContractKind.NUMBER,
        ContractKind.BOOL,
        ContractKind.INSTANT,
    }
)


@dataclass(frozen=True)
class ValueContract:
    kind: ContractKind
    max_length: int = 4096
    allowed_values: frozenset[str] = frozenset()
    minimum: float = float("-inf")
    maximum: float = float("inf")
    max_items: int = 64
    item_max_length: int = 256
    fields: tuple[tuple[str, "ValueContract"], ...] = ()
    required_fields: frozenset[str] = frozenset()
    max_bytes: int = 16_384

    # -- 构造糖 ---------------------------------------------------------
    @staticmethod
    def text(max_length: int = 256) -> ValueContract:
        return ValueContract(ContractKind.TEXT, max_length=max_length)

    @staticmethod
    def enum(allowed: set[str]) -> ValueContract:
        return ValueContract(ContractKind.ENUM, allowed_values=frozenset(allowed))

    @staticmethod
    def number(minimum: float = float("-inf"), maximum: float = float("inf")) -> ValueContract:
        return ValueContract(ContractKind.NUMBER, minimum=minimum, maximum=maximum)

    @staticmethod
    def boolean() -> ValueContract:
        return ValueContract(ContractKind.BOOL)

    @staticmethod
    def instant(clock: ClockDomain) -> ValueContract:
        return ValueContract(ContractKind.INSTANT, allowed_values=frozenset({clock.value}))

    @staticmethod
    def text_list(max_items: int = 32, item_max_length: int = 128) -> ValueContract:
        return ValueContract(
            ContractKind.TEXT_LIST, max_items=max_items, item_max_length=item_max_length
        )

    @staticmethod
    def enum_list(allowed: set[str], max_items: int = 32) -> ValueContract:
        return ValueContract(
            ContractKind.ENUM_LIST,
            allowed_values=frozenset(allowed),
            max_items=max_items,
        )

    @staticmethod
    def record(fields: dict[str, "ValueContract"], required: set[str]) -> ValueContract:
        for name, sub in fields.items():
            if sub.kind not in _SCALAR_KINDS:
                raise ValueError(f"RECORD 字段 {name} 只能是标量契约，收到 {sub.kind.value}")
        return ValueContract(
            ContractKind.RECORD,
            fields=tuple(sorted(fields.items())),
            required_fields=frozenset(required),
        )

    @staticmethod
    def opaque_json(max_bytes: int = 16_384) -> ValueContract:
        return ValueContract(ContractKind.OPAQUE_JSON, max_bytes=max_bytes)

    # -- 校验 -----------------------------------------------------------
    def validate(self, payload: dict[str, Any]) -> str | None:
        """返回错误说明；None 表示形状合法。"""
        if not isinstance(payload, dict):
            return "payload 必须是 JSON 对象"

        if self.kind is ContractKind.OPAQUE_JSON:
            size = len(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            if size > self.max_bytes:
                return f"OPAQUE_JSON 超出 {self.max_bytes} 字节（实际 {size}）"
            return None

        if self.kind is ContractKind.RECORD:
            declared = dict(self.fields)
            missing = sorted(self.required_fields - payload.keys())
            if missing:
                return f"RECORD 缺必填字段 {missing}"
            unknown = sorted(payload.keys() - declared.keys())
            if unknown:
                return f"RECORD 含未声明字段 {unknown}"
            for name, value in payload.items():
                sub = declared[name]
                err = sub.validate({"value": value})
                if err:
                    return f"RECORD 字段 {name}：{err}"
            return None

        if "value" not in payload:
            return "payload 缺 value 键"
        value = payload["value"]

        if self.kind is ContractKind.TEXT:
            if not isinstance(value, str):
                return f"TEXT 需要字符串，收到 {type(value).__name__}"
            if len(value) > self.max_length:
                return f"TEXT 超出 {self.max_length} 字（实际 {len(value)}）"
            return None

        if self.kind is ContractKind.ENUM:
            if not isinstance(value, str):
                return f"ENUM 需要字符串，收到 {type(value).__name__}"
            if value not in self.allowed_values:
                return f"ENUM 值 {value!r} 不在闭集内"
            return None

        if self.kind is ContractKind.NUMBER:
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                return f"NUMBER 需要数字，收到 {type(value).__name__}"
            if not (self.minimum <= float(value) <= self.maximum):
                return f"NUMBER {value} 越界 [{self.minimum}, {self.maximum}]"
            return None

        if self.kind is ContractKind.BOOL:
            if not isinstance(value, bool):
                return f"BOOL 需要布尔，收到 {type(value).__name__}"
            return None

        if self.kind is ContractKind.INSTANT:
            if isinstance(value, bool) or not isinstance(value, int):
                return f"INSTANT 需要整数，收到 {type(value).__name__}"
            clock = payload.get("clock")
            if clock is None:
                return "INSTANT 缺 clock"
            if clock not in self.allowed_values:
                return f"INSTANT clock {clock!r} 与契约声明不符"
            return None

        if self.kind in (ContractKind.TEXT_LIST, ContractKind.ENUM_LIST):
            if not isinstance(value, list):
                return f"{self.kind.value} 需要数组，收到 {type(value).__name__}"
            if len(value) > self.max_items:
                return f"{self.kind.value} 超出 {self.max_items} 项（实际 {len(value)}）"
            for item in value:
                if not isinstance(item, str):
                    return f"{self.kind.value} 元素需要字符串，收到 {type(item).__name__}"
                if self.kind is ContractKind.TEXT_LIST and len(item) > self.item_max_length:
                    return f"元素 {item!r} 超出 {self.item_max_length} 字"
                if self.kind is ContractKind.ENUM_LIST and item not in self.allowed_values:
                    return f"元素 {item!r} 不在闭集内"
            return None

        return f"未知契约类型 {self.kind}"

    # -- 序列化 ---------------------------------------------------------
    def to_json(self) -> str:
        return json.dumps(self._to_dict(), ensure_ascii=False, sort_keys=True)

    def _to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {"kind": self.kind.value}
        if self.kind is ContractKind.TEXT:
            out["maxLength"] = self.max_length
        elif self.kind is ContractKind.ENUM:
            out["allowedValues"] = sorted(self.allowed_values)
        elif self.kind is ContractKind.NUMBER:
            out["min"] = self.minimum
            out["max"] = self.maximum
        elif self.kind is ContractKind.INSTANT:
            out["clock"] = sorted(self.allowed_values)
        elif self.kind is ContractKind.TEXT_LIST:
            out["maxItems"] = self.max_items
            out["itemMaxLength"] = self.item_max_length
        elif self.kind is ContractKind.ENUM_LIST:
            out["allowedValues"] = sorted(self.allowed_values)
            out["maxItems"] = self.max_items
        elif self.kind is ContractKind.RECORD:
            out["fields"] = {name: sub._to_dict() for name, sub in self.fields}
            out["requiredFields"] = sorted(self.required_fields)
        elif self.kind is ContractKind.OPAQUE_JSON:
            out["maxBytes"] = self.max_bytes
        return out

    @staticmethod
    def from_json(raw: str) -> ValueContract:
        return ValueContract._from_dict(json.loads(raw))

    @staticmethod
    def _from_dict(data: dict[str, Any]) -> ValueContract:
        kind = ContractKind(data["kind"])
        if kind is ContractKind.TEXT:
            return ValueContract.text(data.get("maxLength", 256))
        if kind is ContractKind.ENUM:
            return ValueContract.enum(set(data.get("allowedValues", [])))
        if kind is ContractKind.NUMBER:
            return ValueContract.number(
                data.get("min", float("-inf")), data.get("max", float("inf"))
            )
        if kind is ContractKind.BOOL:
            return ValueContract.boolean()
        if kind is ContractKind.INSTANT:
            clocks = data.get("clock", [ClockDomain.WALL_CLOCK.value])
            return ValueContract.instant(ClockDomain(clocks[0]))
        if kind is ContractKind.TEXT_LIST:
            return ValueContract.text_list(
                data.get("maxItems", 32), data.get("itemMaxLength", 128)
            )
        if kind is ContractKind.ENUM_LIST:
            return ValueContract.enum_list(
                set(data.get("allowedValues", [])), data.get("maxItems", 32)
            )
        if kind is ContractKind.RECORD:
            return ValueContract.record(
                {
                    name: ValueContract._from_dict(sub)
                    for name, sub in data.get("fields", {}).items()
                },
                set(data.get("requiredFields", [])),
            )
        return ValueContract.opaque_json(data.get("maxBytes", 16_384))


# --------------------------------------------------------------------------
# 字段目录
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class StateFieldSpec:
    field_id: str
    contract: ValueContract
    allowed_writers: frozenset[MemoryWriterKind]
    overwrite_policy: OverwritePolicy = OverwritePolicy.EXTRACTOR_CAN_CURRENT
    authority_mode: AuthorityMode = AuthorityMode.MEMORY_AUTHORITATIVE
    projection_mode: ProjectionMode = ProjectionMode.NONE
    risk_tier: RiskTier = RiskTier.LOW
    created_by: CreatedBy = CreatedBy.HOST_SEED
    deprecated: bool = False


@dataclass(frozen=True)
class FieldRegistration:
    field_id: str
    created: bool
    reused_via_alias: bool = False


# --------------------------------------------------------------------------
# 写入
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class WriterPrincipal:
    kind: MemoryWriterKind
    id: str
    policy_version: str


@dataclass(frozen=True)
class SourceRef:
    type: SourceType
    id: str


@dataclass(frozen=True)
class RenderedText:
    text: str
    renderer_id: str
    renderer_version: str


@dataclass(frozen=True)
class SemanticCheck:
    checker_id: str
    checker_version: str
    passed: bool


@dataclass(frozen=True)
class EvidenceRef:
    memory_id: str
    relation: EvidenceRelation = EvidenceRelation.SUPPORTS


@dataclass(frozen=True)
class RawEventDraft:
    space_id: str
    owner_id: str
    role: str
    content: str
    clock_domain: ClockDomain
    occurred_at: int | None = None
    session_id: str = ""
    task_scope_id: str = ""
    idempotency_key: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class StateCommand:
    principal: WriterPrincipal
    owner_id: str
    scope: MemoryScope
    scope_id: str
    sources: tuple[SourceRef, ...]
    field_id: str
    payload: dict[str, Any]
    rendered: RenderedText
    valid_from: ClockStamp
    op: StateOp = StateOp.PUT
    expected_current_id: str | None = None
    source_revision: int | None = None
    semantic_check: SemanticCheck | None = None
    override_user_edit: bool = False
    confidence: float = 1.0
    target_lifecycle: TargetLifecycle = TargetLifecycle.CURRENT
    salience: float = 0.5
    tags: tuple[str, ...] = ()


@dataclass(frozen=True)
class EpisodeCommand:
    principal: WriterPrincipal
    owner_id: str
    scope: MemoryScope
    scope_id: str
    sources: tuple[SourceRef, ...]
    rendered: RenderedText
    occurred_at: ClockStamp | None
    idempotency_key: str
    payload: dict[str, Any] = field(default_factory=dict)
    tags: tuple[str, ...] = ()
    salience: float = 0.5
    confidence: float = 1.0
    target_lifecycle: TargetLifecycle = TargetLifecycle.CURRENT


@dataclass(frozen=True)
class ReflectionCommand:
    principal: WriterPrincipal
    owner_id: str
    scope: MemoryScope
    scope_id: str
    sources: tuple[SourceRef, ...]
    memory_key: str
    rendered: RenderedText
    valid_from: ClockStamp
    evidence: tuple[EvidenceRef, ...] = ()
    payload: dict[str, Any] = field(default_factory=dict)
    expected_current_id: str | None = None
    confidence: float = 1.0
    target_lifecycle: TargetLifecycle = TargetLifecycle.CURRENT
    salience: float = 0.5
    tags: tuple[str, ...] = ()


@dataclass(frozen=True)
class RetractCommand:
    principal: WriterPrincipal
    memory_id: str
    reason: str
    retracted_at: ClockStamp | None = None


MemoryCommand = StateCommand | EpisodeCommand | ReflectionCommand | RetractCommand


@dataclass(frozen=True)
class MemoryBatch:
    space_id: str
    writer_run_id: str
    commands: tuple[MemoryCommand, ...]
    extractor_version: str = ""
    commit_mode: CommitMode = CommitMode.ATOMIC


# --------------------------------------------------------------------------
# 提交结果
# --------------------------------------------------------------------------


class CommitErrorCode(str, Enum):
    CLOCK_DOMAIN_MISMATCH = "CLOCK_DOMAIN_MISMATCH"
    MISSING_VALID_FROM = "MISSING_VALID_FROM"
    MISSING_OCCURRED_AT = "MISSING_OCCURRED_AT"
    UNKNOWN_FIELD = "UNKNOWN_FIELD"
    FIELD_DEPRECATED = "FIELD_DEPRECATED"
    AMBIGUOUS_FIELD = "AMBIGUOUS_FIELD"
    SCHEMA_MISMATCH = "SCHEMA_MISMATCH"
    SEMANTIC_CHECK_REQUIRED = "SEMANTIC_CHECK_REQUIRED"
    SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND"
    WRITER_NOT_ALLOWED = "WRITER_NOT_ALLOWED"
    AUTHORITY_VIOLATION = "AUTHORITY_VIOLATION"
    STALE_SOURCE_REVISION = "STALE_SOURCE_REVISION"
    USER_LOCK = "USER_LOCK"
    CAS_CONFLICT = "CAS_CONFLICT"
    PRIORITY_BLOCKED = "PRIORITY_BLOCKED"
    IDEMPOTENT_REPLAY = "IDEMPOTENT_REPLAY"
    IDEMPOTENCY_KEY_MISSING = "IDEMPOTENCY_KEY_MISSING"
    EVIDENCE_REQUIRED = "EVIDENCE_REQUIRED"
    EVIDENCE_NOT_VISIBLE = "EVIDENCE_NOT_VISIBLE"
    SCOPE_NOT_ALLOWED = "SCOPE_NOT_ALLOWED"
    MEMORY_NOT_FOUND = "MEMORY_NOT_FOUND"


@dataclass(frozen=True)
class CommandFailure:
    command_index: int
    code: CommitErrorCode
    detail: str


@dataclass(frozen=True)
class CommittedWrite:
    command_index: int
    memory_id: str
    kind: MemoryKind
    lifecycle: Lifecycle
    is_current: bool
    superseded_id: str | None = None


@dataclass(frozen=True)
class CommitResult:
    ok: bool
    writes: tuple[CommittedWrite, ...] = ()
    failures: tuple[CommandFailure, ...] = ()
    no_ops: tuple[CommittedWrite, ...] = ()

    def failure_codes(self) -> list[CommitErrorCode]:
        return [f.code for f in self.failures]


# --------------------------------------------------------------------------
# 读取
# --------------------------------------------------------------------------


class RequiredStateStatus(str, Enum):
    PRESENT = "PRESENT"
    MISSING = "MISSING"
    CANDIDATE_ONLY = "CANDIDATE_ONLY"
    STALE = "STALE"
    UNAUTHORIZED = "UNAUTHORIZED"
    SCHEMA_MISMATCH = "SCHEMA_MISMATCH"
    UNKNOWN_FIELD = "UNKNOWN_FIELD"
    NOT_PROJECTED = "NOT_PROJECTED"
    AMBIGUOUS_FIELD = "AMBIGUOUS_FIELD"


class OnMissing(str, Enum):
    BLOCK = "BLOCK"
    WARN = "WARN"


@dataclass(frozen=True)
class RequiredField:
    field_id: str
    on_missing: OnMissing = OnMissing.BLOCK


@dataclass(frozen=True)
class StateIssue:
    field_id: str
    status: RequiredStateStatus
    detail: str = ""


@dataclass(frozen=True)
class StateSelector:
    field_id: str
    scope: MemoryScope | None = None
    scope_id: str = ""


@dataclass(frozen=True)
class StateItem:
    memory_id: str
    field_id: str
    owner_id: str
    scope: MemoryScope
    scope_id: str
    payload: dict[str, Any]
    text: str
    valid_from: int | None
    valid_to: int | None
    writer_kind: MemoryWriterKind
    confidence: float


@dataclass(frozen=True)
class StateReadRequest:
    space_id: str
    owner_id: str
    selectors: tuple[StateSelector, ...]
    at: ClockStamp
    include_owners: tuple[str, ...] = ()
    session_id: str = ""
    task_scope_id: str = ""


@dataclass(frozen=True)
class StateReadResult:
    present: dict[str, StateItem]
    issues: tuple[StateIssue, ...] = ()


@dataclass(frozen=True)
class StateHistoryRequest:
    space_id: str
    owner_id: str
    field_id: str
    scope: MemoryScope | None = None
    from_inclusive: int | None = None
    to_exclusive: int | None = None
    include_retracted: bool = False


@dataclass(frozen=True)
class StateVersion:
    memory_id: str
    payload: dict[str, Any]
    text: str
    valid_from: int | None
    valid_to: int | None
    is_current: bool
    lifecycle: Lifecycle
    writer_kind: MemoryWriterKind
    supersedes_id: str | None


@dataclass(frozen=True)
class Message:
    role: str
    content: str


@dataclass(frozen=True)
class RecallRequest:
    space_id: str
    owner_id: str
    query: str
    at: ClockStamp
    include_owners: tuple[str, ...] = ()
    recent_messages: tuple[Message, ...] = ()
    session_id: str = ""
    task_scope_id: str = ""
    kinds: frozenset[MemoryKind] = frozenset(MemoryKind)
    required_fields: tuple[RequiredField, ...] = ()
    context_contract_id: str | None = None
    context_contract_version: str | None = None
    tags: tuple[str, ...] = ()
    budget_chars: int = 2_000
    limit_per_channel: int = 12
    explain: bool = False


class RecallChannel(str, Enum):
    FTS = "FTS"
    RECENT = "RECENT"
    TAG = "TAG"
    VECTOR = "VECTOR"


@dataclass(frozen=True)
class SelectedMemory:
    memory_id: str
    kind: MemoryKind
    owner_id: str
    text: str
    payload: dict[str, Any]
    business_time: int | None
    channels: tuple[RecallChannel, ...]
    rank: int
    score: float


class BlockedReason(str, Enum):
    REQUIRED_STATE_UNAVAILABLE = "REQUIRED_STATE_UNAVAILABLE"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    MISSING_CONTRACT_REF = "MISSING_CONTRACT_REF"


@dataclass(frozen=True)
class Degradation:
    component: str
    reason: str


@dataclass(frozen=True)
class RecallResult:
    ready: bool
    context: str = ""
    required_states: dict[str, StateItem] = field(default_factory=dict)
    selected: tuple[SelectedMemory, ...] = ()
    warnings: tuple[StateIssue, ...] = ()
    degradations: tuple[Degradation, ...] = ()
    blocked_reason: BlockedReason | None = None
    issues: tuple[StateIssue, ...] = ()
    trace: dict[str, Any] | None = None


@dataclass(frozen=True)
class IndexHealth:
    fts_rows: int
    embedding_rows: int
    pending_jobs: int
    failed_jobs: int
    vector_available: bool
