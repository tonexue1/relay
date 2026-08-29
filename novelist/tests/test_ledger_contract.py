"""Ledger 合同验收。

每个测试对应 relay/memory/docs/prd.md §5 的一条，不过不算做成。
"""

from __future__ import annotations

import pytest

from ledger.runtime import LedgerError, MemoryRuntime
from ledger.types import (
    BlockedReason,
    ClockDomain,
    ClockStamp,
    CommitErrorCode,
    Lifecycle,
    MemoryKind,
    MemoryScope,
    MemoryWriterKind,
    OnMissing,
    OverwritePolicy,
    RecallRequest,
    RequiredField,
    RequiredStateStatus,
    StateReadRequest,
    StateSelector,
    TargetLifecycle,
    ValueContract,
)

CONTRACT = {"context_contract_id": "novel.scene", "context_contract_version": "1"}


def read(harness, owner, *fields, at, **kwargs):
    return harness.runtime.get_states(
        StateReadRequest(
            space_id=harness.space_id,
            owner_id=owner,
            selectors=tuple(StateSelector(f) for f in fields),
            at=harness.at(at),
            **kwargs,
        )
    )


def recall(harness, owner, query, at, **kwargs):
    return harness.runtime.recall(
        RecallRequest(
            space_id=harness.space_id,
            owner_id=owner,
            query=query,
            at=harness.at(at),
            **kwargs,
        )
    )


# ---------------------------------------------------------------------------
# 写入：当前值唯一、幂等、时钟
# ---------------------------------------------------------------------------


def test_同字段连续写入只留一个当前值(novel):
    src = novel.capture("林晚", "林晚到了洛阳", at=1)
    novel.commit(novel.state("林晚", "location", "临安", 1, source=src))
    novel.commit(novel.state("林晚", "location", "洛阳", 5, source=src))

    rows = novel.runtime._conn.execute(
        """SELECT COUNT(*) AS n FROM memory_item
           WHERE kind = 'STATE' AND field_id = 'location' AND is_current = 1""",
    ).fetchone()
    assert rows["n"] == 1

    history = novel.runtime.get_state_history(
        __import__("ledger.types", fromlist=["StateHistoryRequest"]).StateHistoryRequest(
            space_id=novel.space_id, owner_id="林晚", field_id="location"
        )
    )
    assert [(h.valid_from, h.valid_to) for h in history] == [(1, 5), (5, None)]


def test_CAS_撞车拒绝而不是静默覆盖(novel):
    src = novel.capture("林晚", "原文", at=1)
    first = novel.commit(novel.state("林晚", "location", "临安", 1, source=src))
    current_id = first.writes[0].memory_id

    novel.commit(novel.state("林晚", "location", "洛阳", 3, source=src))

    stale = novel.commit(
        novel.state("林晚", "location", "扬州", 4, source=src, expected_current_id=current_id)
    )
    assert stale.failure_codes() == [CommitErrorCode.CAS_CONFLICT]


def test_Episode_幂等键含_owner_两角色同键各写一条(novel):
    src = novel.capture(novel_owner := "林晚", "两人在桥上分别", at=7)
    result = novel.commit(
        novel.episode(novel_owner, "我在桥上与沈砚分别", 7, source=src, key="ch7:桥别"),
        novel.episode("沈砚", "我在桥上与林晚分别", 7, source=src, key="ch7:桥别"),
    )
    assert result.ok
    assert len(result.writes) == 2


def test_重放同一批次不产生重复_Episode(novel):
    src = novel.capture("林晚", "原文", at=7)
    command = novel.episode("林晚", "我在桥上与沈砚分别", 7, source=src, key="ch7:桥别")

    first = novel.commit(command)
    replay = novel.commit(command)

    assert first.ok and len(first.writes) == 1
    assert replay.ok and not replay.writes
    assert [w.memory_id for w in replay.no_ops] == [first.writes[0].memory_id]


def test_小说仓缺业务时间直接拒(novel):
    src = novel.capture("林晚", "原文", at=1)
    from ledger.types import EpisodeCommand, RenderedText, SourceRef, SourceType

    naked = EpisodeCommand(
        principal=novel.principal(MemoryWriterKind.EXTRACTOR),
        owner_id="林晚",
        scope=MemoryScope.PROFILE,
        scope_id="",
        sources=(SourceRef(SourceType.RAW_EVENT, src),),
        rendered=RenderedText("无时间的事", "episode.summary", "1"),
        occurred_at=None,
        idempotency_key="ch1:x",
    )
    assert novel.commit(naked).failure_codes() == [CommitErrorCode.MISSING_OCCURRED_AT]


def test_错时钟域写入被拒(novel):
    src = novel.capture("林晚", "原文", at=1)
    wrong = novel.state("林晚", "location", "临安", 1, source=src)
    wrong = type(wrong)(**{**wrong.__dict__, "valid_from": ClockStamp(ClockDomain.WALL_CLOCK, 1)})
    assert novel.commit(wrong).failure_codes() == [CommitErrorCode.CLOCK_DOMAIN_MISMATCH]


def test_来源不存在被拒(novel):
    bad = novel.state("林晚", "location", "临安", 1, source="raw_不存在")
    assert novel.commit(bad).failure_codes() == [CommitErrorCode.SOURCE_NOT_FOUND]


def test_整批原子失败时无残留(novel):
    src = novel.capture("林晚", "原文", at=1)
    result = novel.commit(
        novel.state("林晚", "location", "临安", 1, source=src),
        novel.state("林晚", "未占槽字段", "x", 1, source=src),
    )
    assert result.failure_codes() == [CommitErrorCode.UNKNOWN_FIELD]
    assert read(novel, "林晚", "location", at=9).present == {}


def test_闭集字段越界被拒(novel):
    src = novel.capture("林晚", "原文", at=1)
    bad = novel.state("林晚", "status", "半死不活", 1, source=src)
    assert novel.commit(bad).failure_codes() == [CommitErrorCode.SCHEMA_MISMATCH]


# ---------------------------------------------------------------------------
# 字段目录与别名
# ---------------------------------------------------------------------------


def test_未占槽的字段点不到_占槽后能点到(assistant):
    src = assistant.capture("user", "我喜欢巴拉巴拉", at=None)
    assert assistant.commit(
        assistant.state("user", "巴拉巴拉", "喜欢", 100, source=src)
    ).failure_codes() == [CommitErrorCode.UNKNOWN_FIELD]

    assistant.seed("巴拉巴拉")
    assert assistant.commit(assistant.state("user", "巴拉巴拉", "喜欢", 100, source=src)).ok
    assert "巴拉巴拉" in read(assistant, "user", "巴拉巴拉", at=200).present


def test_别名挂到规范名后读到同一槽(assistant):
    assistant.seed("allergies", ValueContract.text_list())
    assistant.alias("过敏", "allergies")
    src = assistant.capture("user", "我花生过敏", at=None)

    assert assistant.commit(
        assistant.state("user", "过敏", ["花生"], 100, source=src)
    ).ok

    via_alias = read(assistant, "user", "过敏", at=200).present
    via_canonical = read(assistant, "user", "allergies", at=200).present
    assert via_alias["allergies"].memory_id == via_canonical["allergies"].memory_id


def test_别名不得占用已有字段_两槽并存(assistant):
    assistant.seed("allergies")
    assistant.seed("过敏")
    with pytest.raises(LedgerError):
        assistant.alias("过敏", "allergies")


def test_两槽同时有值且不一致时读取报歧义(novel):
    novel.seed("world_location")
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.state("林晚", "location", "临安", 1, source=src),
        novel.state("世界", "location", "洛阳", 1, source=src),
    )
    result = read(novel, "林晚", "location", at=5, include_owners=("世界",))
    assert result.present == {}
    assert result.issues[0].status is RequiredStateStatus.AMBIGUOUS_FIELD


def test_同值跨_owner_不算歧义(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.state("林晚", "location", "临安", 1, source=src),
        novel.state("世界", "location", "临安", 1, source=src),
    )
    result = read(novel, "林晚", "location", at=5, include_owners=("世界",))
    assert result.present["location"].payload["value"] == "临安"


def test_ensure_命中别名时复用规范名不建第二槽(assistant):
    assistant.seed("allergies")
    assistant.alias("过敏", "allergies")
    registration = assistant.runtime.ensure_state_field(
        assistant.space_id,
        __import__("ledger.types", fromlist=["StateFieldSpec"]).StateFieldSpec(
            field_id="过敏",
            contract=ValueContract.text(),
            allowed_writers=frozenset({MemoryWriterKind.EXTRACTOR}),
        ),
    )
    assert registration.field_id == "allergies"
    assert registration.reused_via_alias


# ---------------------------------------------------------------------------
# USER_LOCK
# ---------------------------------------------------------------------------


def test_用户手改后抽取器跨_scope_也不能升当前值(assistant):
    assistant.seed(
        "allergies", ValueContract.text_list(), policy=OverwritePolicy.USER_LOCK
    )
    src = assistant.capture("user", "我花生过敏", at=None)

    assert assistant.commit(
        assistant.state(
            "user", "allergies", ["花生"], 100, source=src, writer=MemoryWriterKind.USER_EDIT
        )
    ).ok

    blocked = assistant.commit(
        assistant.state(
            "user",
            "allergies",
            ["无"],
            200,
            source=src,
            writer=MemoryWriterKind.EXTRACTOR,
            scope=MemoryScope.SESSION,
            scope_id="s1",
        )
    )
    assert blocked.failure_codes() == [CommitErrorCode.USER_LOCK]

    as_candidate = assistant.commit(
        assistant.state(
            "user",
            "allergies",
            ["无"],
            200,
            source=src,
            writer=MemoryWriterKind.EXTRACTOR,
            target=TargetLifecycle.CANDIDATE,
        )
    )
    assert as_candidate.ok
    assert as_candidate.writes[0].lifecycle is Lifecycle.CANDIDATE

    still = read(assistant, "user", "allergies", at=300).present
    assert still["allergies"].payload["value"] == ["花生"]


def test_宿主规则可显式强推用户手改(assistant):
    assistant.seed(
        "allergies", ValueContract.text_list(), policy=OverwritePolicy.USER_LOCK
    )
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(
        assistant.state(
            "user", "allergies", ["花生"], 100, source=src, writer=MemoryWriterKind.USER_EDIT
        )
    )
    forced = assistant.commit(
        assistant.state(
            "user",
            "allergies",
            ["花生", "海鲜"],
            200,
            source=src,
            writer=MemoryWriterKind.HOST_RULE,
            override_user_edit=True,
        )
    )
    assert forced.ok


def test_不在白名单的写入者被拒(assistant):
    assistant.seed("allergies", writers={MemoryWriterKind.USER_EDIT})
    src = assistant.capture("user", "原文", at=None)
    denied = assistant.commit(
        assistant.state(
            "user", "allergies", "花生", 100, source=src, writer=MemoryWriterKind.EXTRACTOR
        )
    )
    assert denied.failure_codes() == [CommitErrorCode.WRITER_NOT_ALLOWED]


# ---------------------------------------------------------------------------
# 点时读取
# ---------------------------------------------------------------------------


def test_点时读取取当时的值而非全书最后一次写入(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.state("林晚", "location", "临安", 10, source=src))
    novel.commit(novel.state("林晚", "location", "洛阳", 80, source=src))

    assert read(novel, "林晚", "location", at=30).present["location"].payload["value"] == "临安"
    assert read(novel, "林晚", "location", at=90).present["location"].payload["value"] == "洛阳"


def test_写入时间之前读不到该值(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.state("林晚", "location", "临安", 80, source=src))
    assert read(novel, "林晚", "location", at=30).present == {}


def test_CANDIDATE_永不作为当前值(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.state(
            "林晚", "location", "临安", 1, source=src, target=TargetLifecycle.CANDIDATE
        )
    )
    result = read(novel, "林晚", "location", at=5)
    assert result.present == {}
    assert result.issues[0].status is RequiredStateStatus.CANDIDATE_ONLY


def test_SESSION_覆盖_PROFILE(assistant):
    assistant.seed("mood")
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(
        assistant.state("user", "mood", "平静", 100, source=src),
        assistant.state(
            "user", "mood", "焦虑", 100, source=src, scope=MemoryScope.SESSION, scope_id="s1"
        ),
    )
    with_session = read(assistant, "user", "mood", at=200, session_id="s1").present
    without = read(assistant, "user", "mood", at=200).present
    assert with_session["mood"].payload["value"] == "焦虑"
    assert without["mood"].payload["value"] == "平静"


def test_跨_owner_默认读不到(novel):
    src = novel.capture("沈砚", "原文", at=1)
    novel.commit(novel.state("沈砚", "location", "洛阳", 1, source=src))
    assert read(novel, "林晚", "location", at=5).present == {}


# ---------------------------------------------------------------------------
# 召回：必带闭环
# ---------------------------------------------------------------------------


def test_必带缺失时_Blocked_而不是空结果(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.episode("林晚", "我在临安见了沈砚", 1, source=src, key="ch1:见面"))

    result = recall(
        novel,
        "林晚",
        "沈砚",
        at=5,
        required_fields=(RequiredField("location"),),
        **CONTRACT,
    )
    assert not result.ready
    assert result.blocked_reason is BlockedReason.REQUIRED_STATE_UNAVAILABLE
    assert result.issues[0].field_id == "location"


def test_必带传了却没带契约版本也是_Blocked(novel):
    result = recall(novel, "林晚", "x", at=5, required_fields=(RequiredField("location"),))
    assert not result.ready
    assert result.blocked_reason is BlockedReason.MISSING_CONTRACT_REF


def test_WARN_策略的必带缺失只告警不阻断(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.state("林晚", "location", "临安", 1, source=src))
    result = recall(
        novel,
        "林晚",
        "临安",
        at=5,
        required_fields=(
            RequiredField("location"),
            RequiredField("current_goal", OnMissing.WARN),
        ),
        **CONTRACT,
    )
    assert result.ready
    assert "location" in result.required_states
    assert [w.field_id for w in result.warnings] == ["current_goal"]


def test_预算极小时必带仍在上下文里(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.state("林晚", "location", "临安", 1, source=src))
    for index in range(6):
        novel.commit(
            novel.episode("林晚", f"第{index}件无关小事发生在临安", 2, source=src, key=f"e{index}")
        )

    result = recall(
        novel,
        "林晚",
        "临安",
        at=5,
        required_fields=(RequiredField("location"),),
        budget_chars=12,
        **CONTRACT,
    )
    assert result.ready
    assert "临安" in result.context
    assert result.selected == ()


def test_必带走别名解析(assistant):
    assistant.seed("allergies", ValueContract.text_list())
    assistant.alias("过敏", "allergies")
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(assistant.state("user", "allergies", ["花生"], 100, source=src))

    result = assistant.runtime.recall(
        RecallRequest(
            space_id=assistant.space_id,
            owner_id="user",
            query="点外卖",
            at=assistant.at(200),
            required_fields=(RequiredField("过敏"),),
            context_contract_id="assistant.order",
            context_contract_version="1",
        )
    )
    assert result.ready
    assert result.required_states["allergies"].payload["value"] == ["花生"]


# ---------------------------------------------------------------------------
# 召回：四路搜索的硬过滤
# ---------------------------------------------------------------------------


def test_未来章节的_Episode_不出现(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.episode("林晚", "我在临安遇见沈砚", 10, source=src, key="ch10"),
        novel.episode("林晚", "我在洛阳杀了沈砚", 80, source=src, key="ch80"),
    )
    result = recall(novel, "林晚", "沈砚", at=30)
    texts = [s.text for s in result.selected]
    assert any("遇见" in t for t in texts)
    assert not any("杀了" in t for t in texts)


def test_已闭窗的旧住址不进搜索(assistant):
    assistant.seed("address")
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(assistant.state("user", "address", "朝阳区望京", 100, source=src))
    assistant.commit(assistant.state("user", "address", "海淀区中关村", 200, source=src))

    result = recall(assistant, "user", "望京 中关村", at=300)
    texts = " ".join(s.text for s in result.selected)
    assert "中关村" in texts
    assert "望京" not in texts


def test_跨_owner_搜索默认零条(novel):
    src = novel.capture("沈砚", "原文", at=1)
    novel.commit(novel.episode("沈砚", "我独自去了洛阳赴任", 5, source=src, key="ch5"))

    isolated = recall(novel, "林晚", "洛阳 赴任", at=10)
    assert isolated.selected == ()

    shared = recall(novel, "林晚", "洛阳 赴任", at=10, include_owners=("沈砚",))
    assert [s.owner_id for s in shared.selected] == ["沈砚"]


def test_新会话带不上上周_SESSION_的内容(assistant):
    assistant.seed("plan")
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(
        assistant.state(
            "user",
            "plan",
            "今晚打算去看电影",
            100,
            source=src,
            scope=MemoryScope.SESSION,
            scope_id="上周会话",
        )
    )
    stale = recall(assistant, "user", "今晚打算", at=200, session_id="本周会话")
    assert stale.selected == ()

    same = recall(assistant, "user", "今晚打算", at=200, session_id="上周会话")
    assert len(same.selected) == 1


def test_没传_session_id_时不兜底空串(assistant):
    assistant.seed("plan")
    src = assistant.capture("user", "原文", at=None)
    assistant.commit(
        assistant.state(
            "user", "plan", "今晚打算", 100, source=src, scope=MemoryScope.SESSION, scope_id=""
        )
    )
    assert recall(assistant, "user", "今晚打算", at=200).selected == ()


def test_跨_space_恒为空(tmp_path):
    runtime = MemoryRuntime(tmp_path / "two.db")
    runtime.ensure_space("a", ClockDomain.WALL_CLOCK)
    runtime.ensure_space("b", ClockDomain.WALL_CLOCK)

    from conftest import Harness

    space_a = Harness(runtime, "a", ClockDomain.WALL_CLOCK)
    src = space_a.capture("user", "原文", at=None)
    space_a.commit(space_a.episode("user", "只属于 a 的事", 1, source=src, key="k"))

    space_b = Harness(runtime, "b", ClockDomain.WALL_CLOCK)
    assert recall(space_b, "user", "只属于", at=5).selected == ()
    runtime.close()


def test_中文查询能命中(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.episode("林晚", "沈砚在城南的破庙里留下一封信", 3, source=src, key="ch3"))
    result = recall(novel, "林晚", "破庙", at=10)
    assert len(result.selected) == 1


def test_Tag_通道命中的条目带_TAG_归属(novel):
    from ledger.types import RecallChannel

    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.episode("林晚", "毫不相干的一段闲笔", 3, source=src, key="ch3", tags=("伏笔",)),
        novel.episode("林晚", "另一段没标签的闲笔", 3, source=src, key="ch3b"),
    )

    tagged = recall(novel, "林晚", "对不上的查询词", at=10, tags=("伏笔",))
    by_tag = [s for s in tagged.selected if RecallChannel.TAG in s.channels]
    assert [s.text for s in by_tag] == ["毫不相干的一段闲笔"]
    # 命中两路的排在只命中一路的前面。
    assert tagged.selected[0].text == "毫不相干的一段闲笔"

    untagged = recall(novel, "林晚", "对不上的查询词", at=10)
    assert all(RecallChannel.TAG not in s.channels for s in untagged.selected)


def test_撤回后不再出现在搜索里(novel):
    from ledger.types import RetractCommand

    src = novel.capture("林晚", "原文", at=1)
    written = novel.commit(novel.episode("林晚", "一段要撤回的往事", 3, source=src, key="ch3"))
    memory_id = written.writes[0].memory_id

    novel.commit(
        RetractCommand(
            principal=novel.principal(MemoryWriterKind.HOST_RULE),
            memory_id=memory_id,
            reason="作者删稿",
            retracted_at=novel.at(4),
        )
    )
    assert recall(novel, "林晚", "往事", at=10).selected == ()


# ---------------------------------------------------------------------------
# Reflection
# ---------------------------------------------------------------------------


def test_无证据的_Reflection_不能升当前值(novel):
    src = novel.capture("林晚", "原文", at=1)
    denied = novel.commit(
        novel.reflection("林晚", "self_model", "我信不过任何人", 5, source=src)
    )
    assert denied.failure_codes() == [CommitErrorCode.EVIDENCE_REQUIRED]

    allowed = novel.commit(
        novel.reflection(
            "林晚",
            "self_model",
            "我信不过任何人",
            5,
            source=src,
            target=TargetLifecycle.CANDIDATE,
        )
    )
    assert allowed.ok
    assert allowed.writes[0].lifecycle is Lifecycle.CANDIDATE


def test_有证据的_Reflection_可升当前值并留历史(novel):
    src = novel.capture("林晚", "原文", at=1)
    episode = novel.commit(
        novel.episode("林晚", "沈砚没有来赴约", 3, source=src, key="ch3")
    ).writes[0]

    first = novel.commit(
        novel.reflection(
            "林晚", "self_model", "我大概被抛弃了", 4, source=src, evidence=(episode.memory_id,)
        )
    )
    assert first.ok

    second = novel.commit(
        novel.reflection(
            "林晚", "self_model", "他是被迫的，我错怪了他", 60, source=src, evidence=(episode.memory_id,)
        )
    )
    assert second.ok

    early = recall(novel, "林晚", "抛弃", at=10, kinds=frozenset({MemoryKind.REFLECTION}))
    late = recall(novel, "林晚", "错怪", at=70, kinds=frozenset({MemoryKind.REFLECTION}))
    assert "被抛弃" in early.context
    assert "错怪" in late.context
    assert "被抛弃" not in late.context


# ---------------------------------------------------------------------------
# 降级
# ---------------------------------------------------------------------------


def test_没有向量时必带与_FTS_仍可用(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(
        novel.state("林晚", "location", "临安", 1, source=src),
        novel.episode("林晚", "我在临安的雨里等了很久", 2, source=src, key="ch2"),
    )
    result = recall(
        novel,
        "林晚",
        "临安 雨",
        at=5,
        required_fields=(RequiredField("location"),),
        **CONTRACT,
    )
    assert result.ready
    assert result.required_states["location"].payload["value"] == "临安"
    assert result.selected
    assert [d.component for d in result.degradations] == ["vector"]


def test_向量通道与其他通道用同一套硬过滤(novel):
    novel.runtime.register_embedding_model("fake", dimensions=3)
    src = novel.capture("林晚", "原文", at=1)
    written = novel.commit(
        novel.episode("林晚", "第十章的事", 10, source=src, key="ch10"),
        novel.episode("林晚", "第八十章的事", 80, source=src, key="ch80"),
    )
    for write in written.writes:
        novel.runtime.put_embedding(write.memory_id, "fake", [1.0, 0.0, 0.0])
    novel.runtime.set_query_vector([1.0, 0.0, 0.0])

    result = recall(novel, "林晚", "无关词", at=30)
    assert [s.text for s in result.selected] == ["第十章的事"]


def test_index_health_报告索引状态(novel):
    src = novel.capture("林晚", "原文", at=1)
    novel.commit(novel.episode("林晚", "一件事", 1, source=src, key="k"))
    health = novel.runtime.index_health(novel.space_id)
    assert health.fts_rows == 1
    assert health.pending_jobs == 1
    assert not health.vector_available


# ---------------------------------------------------------------------------
# 原文生命周期
# ---------------------------------------------------------------------------


def test_原文默认_PENDING_不自动消费(novel):
    from ledger.types import ProcessingState

    src = novel.capture("林晚", "原文", at=1)
    assert novel.runtime.raw_event_state(src) is ProcessingState.PENDING

    novel.commit(novel.episode("林晚", "一件事", 1, source=src, key="k"))
    assert novel.runtime.raw_event_state(src) is ProcessingState.PENDING

    novel.runtime.set_raw_event_state(src, ProcessingState.COMMITTED)
    assert novel.runtime.raw_event_state(src) is ProcessingState.COMMITTED


def test_原文幂等键复用同一条(novel):
    first = novel.capture("林晚", "同一章稿", at=1, idempotency_key="ch1")
    again = novel.capture("林晚", "同一章稿", at=1, idempotency_key="ch1")
    assert first == again


def test_未知_space_直接报错(tmp_path):
    runtime = MemoryRuntime(tmp_path / "empty.db")
    with pytest.raises(LedgerError):
        runtime.space_clock("nope")
    runtime.close()
