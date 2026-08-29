"""剧组编排验收（离线，假模型）。

这里验的是"编排和护栏对不对"，不是"写得好不好"。
凡是能在离线模式下被闸门拦住的，都说明编排本身有问题。
"""

from __future__ import annotations

import re

import pytest

from crew.bible_io import load_bible
from crew.clock import chapter_end, story_time
from crew.director import Director, SceneBlocked
from crew.gate import ContinuityGate
from crew.models import (
    ActionProposal,
    ActorBrief,
    Beat,
    Bible,
    CharacterSpec,
    FieldSeed,
    ViolationCode,
    WORLD_OWNER,
)
from crew.studio import Studio, StudioConfig
from ledger.runtime import MemoryRuntime
from ledger.types import (
    ClockDomain,
    ClockStamp,
    MemoryKind,
    RecallRequest,
    StateHistoryRequest,
)
from llm.fake import build_fake_provider
from llm.provider import Role

BIBLE_PATH = "bible/linwan.yaml"


@pytest.fixture
def bible() -> Bible:
    return load_bible(BIBLE_PATH)


@pytest.fixture
def written(tmp_path, bible):
    """跑完三章的记忆仓，多个测试共用。"""
    runtime = MemoryRuntime(tmp_path / "offline.db")
    studio = Studio(
        runtime=runtime,
        llm=build_fake_provider(bible.playable_names, bible.total_chapters),
        bible=bible,
        config=StudioConfig(beats_per_chapter=1, chars_per_action=80, min_chapter_length=150),
    )
    outline = studio.prepare()
    results = [studio.write_chapter(chapter, outline) for chapter in (1, 2, 3)]
    yield runtime, bible, results
    runtime.close()


def _recall(runtime, bible, owner, query, at, **kwargs):
    return runtime.recall(
        RecallRequest(
            space_id=bible.space_id,
            owner_id=owner,
            query=query,
            at=ClockStamp(ClockDomain.STORY_TIME, at),
            limit_per_channel=50,
            budget_chars=8_000,
            **kwargs,
        )
    )


# ---------------------------------------------------------------------------
# 端到端
# ---------------------------------------------------------------------------


def test_三章都产出正文并落库(written):
    runtime, bible, results = written
    assert [r.chapter for r in results] == [1, 2, 3]
    for result in results:
        assert result.prose.strip()
        assert result.committed_memories > 0


def test_没有动作被闸门拦下(written):
    """假模型是守规矩的，所以任何拦截都说明编排写错了。"""
    _, _, results = written
    for result in results:
        for scene in result.scenes:
            assert scene.gate_rejections == (), scene.gate_rejections


def test_未上场的角色不会长出经历(written):
    runtime, bible, _ = written
    # 假规划把同地点的人凑一场，沈砚在洛阳，全程没上场。
    result = _recall(
        runtime,
        bible,
        "沈砚",
        "把话说出口",
        chapter_end(3),
        kinds=frozenset({MemoryKind.EPISODE}),
    )
    assert result.selected == ()


def test_演员读不到别人仓里的经历(written):
    runtime, bible, _ = written
    linwan = _recall(
        runtime, bible, "林晚", "把话说出口", chapter_end(3),
        kinds=frozenset({MemoryKind.EPISODE}),
    )
    assert linwan.selected
    assert {s.owner_id for s in linwan.selected} == {"林晚"}


def test_世界仓要显式_include_owners_才读得到(written):
    runtime, bible, _ = written
    isolated = _recall(
        runtime, bible, "林晚", "当面把事情摊开", chapter_end(3),
        kinds=frozenset({MemoryKind.EPISODE}),
    )
    assert WORLD_OWNER not in {s.owner_id for s in isolated.selected}

    shared = _recall(
        runtime, bible, "林晚", "当面把事情摊开", chapter_end(3),
        kinds=frozenset({MemoryKind.EPISODE}),
        include_owners=(WORLD_OWNER,),
    )
    assert WORLD_OWNER in {s.owner_id for s in shared.selected}


def test_未来章节的经历不会漏进当前章(written):
    runtime, bible, _ = written
    at_chapter_one = _recall(
        runtime, bible, "林晚", "把话说出口", story_time(1, 9),
        kinds=frozenset({MemoryKind.EPISODE}),
    )
    texts = " ".join(s.text for s in at_chapter_one.selected)
    assert "第1章" in texts
    assert "第2章" not in texts
    assert "第3章" not in texts


def test_反思按故事时间取当时那一版(written):
    runtime, bible, _ = written
    early = _recall(
        runtime, bible, "林晚", "不再指望", story_time(1, 9),
        kinds=frozenset({MemoryKind.REFLECTION}),
    )
    late = _recall(
        runtime, bible, "林晚", "不再指望", chapter_end(3),
        kinds=frozenset({MemoryKind.REFLECTION}),
    )
    assert "第1章之后" in " ".join(s.text for s in early.selected)
    assert "第3章之后" in " ".join(s.text for s in late.selected)
    assert "第1章之后" not in " ".join(s.text for s in late.selected)


def test_状态覆盖留下完整版本链(written):
    runtime, bible, _ = written
    history = runtime.get_state_history(
        StateHistoryRequest(
            space_id=bible.space_id, owner_id="林晚", field_id="current_goal"
        )
    )
    assert len(history) >= 2
    # 除最后一版外都必须闭窗，且区间不能是零宽。
    for version in history[:-1]:
        assert version.valid_to is not None
        assert version.valid_to > version.valid_from
    assert history[-1].is_current
    assert history[-1].valid_to is None


def test_两类原文都不会烂在待抽取队列里(written):
    """动作序列被抽取消费，正文压根不是抽取源、落库即终态。

    留 PENDING 的那一类会永久堆在待抽取队列里，那个队列就再也说明不了任何问题。
    """
    from crew.policy import ROLE_ACTIONS, ROLE_PROSE

    runtime, bible, _ = written
    for role in (ROLE_ACTIONS, ROLE_PROSE):
        rows = runtime._conn.execute(
            "SELECT processing_state FROM raw_event WHERE role = ?", (role,)
        ).fetchall()
        assert rows, f"{role} 一条都没落"
        assert {r["processing_state"] for r in rows} == {"COMMITTED"}, role

    assert runtime.pending_raw_events(bible.space_id) == []


def test_重跑同一章不产生重复记忆(tmp_path, bible):
    runtime = MemoryRuntime(tmp_path / "replay.db")
    studio = Studio(
        runtime=runtime,
        llm=build_fake_provider(bible.playable_names, bible.total_chapters),
        bible=bible,
        config=StudioConfig(beats_per_chapter=1, chars_per_action=80, min_chapter_length=150),
    )
    outline = studio.prepare()
    studio.write_chapter(1, outline)
    before = runtime._conn.execute(
        "SELECT COUNT(*) AS n FROM memory_item WHERE kind = 'EPISODE'"
    ).fetchone()["n"]

    studio.write_chapter(1, outline)
    after = runtime._conn.execute(
        "SELECT COUNT(*) AS n FROM memory_item WHERE kind = 'EPISODE'"
    ).fetchone()["n"]

    assert after == before
    runtime.close()


# ---------------------------------------------------------------------------
# L1 闸门
# ---------------------------------------------------------------------------


def _brief(**kwargs) -> ActorBrief:
    defaults = dict(
        owner_id="林晚",
        persona="绣娘",
        voice="话短",
        secrets=(),
        scene_goal="问清楚",
        required_states={"location": "临安"},
        memory_context="",
        citable={"mem_real": "我寄了三十六封信"},
        partners=("周娘子",),
    )
    defaults.update(kwargs)
    return ActorBrief(**defaults)


def _beat(**kwargs) -> Beat:
    defaults = dict(
        beat_id="ch1:b1",
        chapter=1,
        scene_index=0,
        goal="问清楚",
        cast=("林晚", "周娘子"),
        location="临安",
    )
    defaults.update(kwargs)
    return Beat(**defaults)


def test_闸门拦下凭空编造的往事引用(bible):
    gate = ContinuityGate(bible)
    report = gate.check(
        ActionProposal(
            owner_id="林晚",
            intent="她把信推回去",
            cites=("mem_编造的",),
            claimed_location="临安",
        ),
        _brief(),
        _beat(),
    )
    assert not report.ok
    assert [v.code for v in report.violations] == [ViolationCode.UNCITABLE_MEMORY]


def test_闸门拦下与必带状态冲突的自述位置(bible):
    gate = ContinuityGate(bible)
    report = gate.check(
        ActionProposal(owner_id="林晚", intent="她推门进来", claimed_location="洛阳"),
        _brief(),
        _beat(),
    )
    assert [v.code for v in report.violations] == [ViolationCode.LOCATION_CONFLICT]


def test_闸门拦下对不在场的人说话(bible):
    gate = ContinuityGate(bible)
    report = gate.check(
        ActionProposal(
            owner_id="林晚",
            intent="她开口",
            claimed_location="临安",
            addresses=("沈砚",),
        ),
        _brief(),
        _beat(),
    )
    assert [v.code for v in report.violations] == [ViolationCode.ABSENT_CHARACTER]


def test_闸门放过无名配角(bible):
    """守门兵、船家这类配角不在设定里，也不该为了过闸门被写成主角。"""
    gate = ContinuityGate(bible)
    report = gate.check(
        ActionProposal(
            owner_id="林晚",
            intent="她把路引递给守门兵",
            claimed_location="临安",
            position_detail="城南城门门洞",
            addresses=("守门兵",),
            mentions=("李四",),
        ),
        _brief(),
        _beat(),
    )
    assert report.ok


def test_闸门放过合规动作(bible):
    gate = ContinuityGate(bible)
    report = gate.check(
        ActionProposal(
            owner_id="林晚",
            intent="她把三十六封信摊在桌上",
            dialogue="你看看这些。",
            cites=("mem_real",),
            claimed_location="临安",
            addresses=("周娘子",),
            mentions=("沈砚",),
        ),
        _brief(),
        _beat(),
    )
    assert report.ok


# ---------------------------------------------------------------------------
# 拍级调度 + 章级成文
# ---------------------------------------------------------------------------


def _play(tmp_path, bible, name="beats.db", **overrides):
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    runtime = MemoryRuntime(tmp_path / name)
    config = dict(
        beats_per_chapter=1,
        chars_per_action=80,
        min_chapter_length=150,
        max_replans=0,
    )
    config.update(overrides)
    studio = Studio(
        runtime=runtime, llm=provider, bible=bible, config=StudioConfig(**config)
    )
    result = studio.write_chapter(1, studio.prepare())
    return runtime, provider, result


def _cues(provider) -> list:
    return [c for c in provider.transcript.by_role(Role.DIRECTOR) if "这是第 " in c.user]


def _split_onstage(user: str) -> list[str]:
    match = re.search(r"在场：(.+)", user)
    return [p.strip() for p in match.group(1).replace("，", "、").split("、") if p.strip()]


def _frames(provider) -> list:
    return [
        c
        for c in provider.transcript.by_role(Role.DIRECTOR)
        if "REACHED|CONTINUE|STALLED" not in c.user and "这是第 " not in c.user
    ]


def test_导演说收场就收_不跑满上限(tmp_path, bible):
    """拍级调度的意义就在这儿：够了就收，不为凑拍数再加对峙。"""
    runtime, provider, result = _play(tmp_path, bible, max_beats=8)
    scene = result.scenes[0]
    cast = len(scene.beat.cast)

    assert scene.rounds == cast, "假导演一人一拍，应该正好演 cast 拍"
    assert scene.rounds < 8, "没有提前收场，跑到了硬上限"
    # 每演一拍问一次，加最后那次收场。
    assert len(_cues(provider)) == cast + 1
    runtime.close()


def test_顶到拍数上限会报出来(tmp_path, bible):
    """真跑上两个 beat 都顶到了上限、导演一次 end 都没说过，攒出的动作一路顶到
    叙述者那一次调用的输出预算，正文一个字没吐出来。这事得看得见。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    base = provider.override(Role.DIRECTOR, lambda s, u: None)

    def never_ends(system: str, user: str):
        if '"act 或 end"' in user:
            cue = base(system, user)
            if cue.get("action") == "end":
                # 硬凑一个永不收场的导演：始终点第一个在场的人。
                cast = _split_onstage(user)
                return {"action": "act", "actor": cast[0], "reason": "再演一拍"}
            return cue
        return base(system, user)

    provider.override(Role.DIRECTOR, never_ends)
    runtime = MemoryRuntime(tmp_path / "ceiling.db")
    lines: list[str] = []
    studio = Studio(
        runtime=runtime,
        llm=provider,
        bible=bible,
        config=StudioConfig(
            beats_per_chapter=1, min_chapter_length=150, max_replans=0, max_beats=3
        ),
        report=lines.append,
    )
    result = studio.write_chapter(1, studio.prepare())

    assert result.scenes[0].rounds == 3, "没演满上限，这条测试没测到东西"
    assert any("顶到 3 拍上限" in line for line in lines), lines
    runtime.close()


def test_导演收场与判据不一致时报出来(tmp_path, bible):
    """收场和判据是两个问题，可以合法地不一致。拍级调度下收场归导演管，所以不回去
    补演——但不一致得报出来，否则 CONTINUE 就是个悄悄被丢掉的信号。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    base = provider.override(Role.DIRECTOR, lambda s, u: None)

    def judge_continue(system: str, user: str):
        if "REACHED|CONTINUE|STALLED" in user:
            return {"status": "CONTINUE", "reason": "城门尚未合上"}
        return base(system, user)

    provider.override(Role.DIRECTOR, judge_continue)
    runtime = MemoryRuntime(tmp_path / "disagree.db")
    lines: list[str] = []
    studio = Studio(
        runtime=runtime,
        llm=provider,
        bible=bible,
        config=StudioConfig(
            beats_per_chapter=1, min_chapter_length=150, max_replans=0
        ),
        report=lines.append,
    )
    result = studio.write_chapter(1, studio.prepare())

    assert any("判据未走完" in line for line in lines), lines
    # 不因为判据没走完就丢掉这一场。
    assert len(result.scenes) == 1
    assert result.prose.strip()
    runtime.close()


def test_章篇幅有上限_不随动作数无限涨(tmp_path, bible):
    """按动作数线性给篇幅会无上限地涨：演满拍的一章能要到三千多字，
    那既超出单次输出的合理预算，也不是一章该有的长度。"""
    runtime, provider, _ = _play(
        tmp_path,
        bible,
        name="cap.db",
        beats_per_chapter=2,
        chars_per_action=1_000,
        max_chapter_length=900,
    )
    narrated = provider.transcript.by_role(Role.NARRATOR)[0].user
    assert "900 字左右" in narrated, narrated[-200:]
    runtime.close()


def test_快到拍数上限时导演会被提醒(tmp_path, bible):
    """不提醒它就一路演到被硬截。上限设 3，第 2 拍起就该提醒。"""
    runtime, provider, _ = _play(tmp_path, bible, name="pressure.db", max_beats=3)
    cues = _cues(provider)
    assert "只剩" not in cues[0].user, "第一拍就催收场，会把戏催没了"
    assert "只剩 2 拍" in cues[1].user, cues[1].user
    runtime.close()


def test_一场只搭一次场_不是每拍搭一次(tmp_path, bible):
    """搭场里含每个演员一次 POV recall，按拍重搭既贵、又会让"已知"在场内漂移。"""
    runtime, provider, _ = _play(tmp_path, bible)
    assert len(_frames(provider)) == 1
    runtime.close()


def test_后演的人看得到先演的人刚做了什么(tmp_path, bible):
    """真模型上撞过：周娘子往包袱里塞干粮，而林晚已经推门走了。

    两个动作不可能是在回应对方，最后是叙述者偷偷重排顺序把矛盾圆过去，
    等于它替导演干了活。
    """
    runtime, provider, result = _play(tmp_path, bible)
    assert len(result.scenes[0].beat.cast) >= 2, "这条测试需要至少两个演员"

    actor_calls = provider.transcript.by_role(Role.ACTOR)
    assert "本场已经发生" not in actor_calls[0].user, "第一拍不该有前情"
    for i, call in enumerate(actor_calls[1:], start=1):
        assert "本场已经发生" in call.user, f"第 {i + 1} 拍看不到前面的人"
        lines = call.user.count("\n- ")
        assert lines >= i, f"第 {i + 1} 拍只看到 {lines} 条，应有 {i} 条"
    runtime.close()


def test_导演点了不在场的人不会硬演(tmp_path, bible):
    """点空当成"没给出有效指令"，连着点空就收场，不能拿它当角色名去搭 brief。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    base = provider.override(Role.DIRECTOR, lambda s, u: None)

    def director(system: str, user: str):
        if '"act 或 end"' in user:
            return {"action": "act", "actor": "隔壁老王", "reason": "他不在场"}
        return base(system, user)

    provider.override(Role.DIRECTOR, director)
    runtime = MemoryRuntime(tmp_path / "ghost.db")
    studio = Studio(
        runtime=runtime,
        llm=provider,
        bible=bible,
        config=StudioConfig(
            beats_per_chapter=1, min_chapter_length=150, max_replans=0, max_beats=8
        ),
    )
    result = studio.write_chapter(1, studio.prepare())

    assert provider.transcript.by_role(Role.ACTOR) == [], "不在场的人被推上台了"
    assert result.scenes == ()
    assert len(_cues(provider)) <= 3, "点空没收敛，一直问到了上限"
    runtime.close()


def test_一章只落一次笔(tmp_path, bible):
    """一场一次落笔的话，一章里会有好几段各自生成的正文，接缝是真实存在的——
    真模型上撞过，同一章"踩石阶"写了两遍。"""
    runtime, provider, result = _play(tmp_path, bible, beats_per_chapter=2)

    assert len(result.scenes) == 2, "这条测试需要一章两场"
    assert len(provider.transcript.by_role(Role.NARRATOR)) == 1
    # 整章正文是一次调用的产物，两场的动作都在里面。
    narrated = provider.transcript.by_role(Role.NARRATOR)[0].user
    assert "本章分 2 场" in narrated
    assert result.prose.strip()
    runtime.close()


def test_事实源是动作序列不是正文(tmp_path, bible):
    """正文是叙述者对动作序列的一次渲染，它多写的东西没过 L1/L2。
    从正文抽记忆，叙述者一加戏、加出来的戏就进了账本。"""
    runtime, provider, _ = _play(tmp_path, bible)

    extract = provider.transcript.by_role(Role.CHRONICLER)
    assert len(extract) == 1
    assert "动作序列" in extract[0].user

    calls = provider.transcript.calls
    first_narrate = next(i for i, c in enumerate(calls) if c.role == Role.NARRATOR)
    first_extract = next(i for i, c in enumerate(calls) if c.role == Role.CHRONICLER)
    assert first_extract < first_narrate, "抽取排在成文之后，说明它读的是正文"
    runtime.close()


def test_正文和动作序列分开落库(tmp_path, bible):
    """两类原文同存在世界仓下。混在一起，承接锚点会拿动作序列当上一章的语气。"""
    from crew.policy import ROLE_ACTIONS, ROLE_PROSE

    runtime, _, result = _play(tmp_path, bible)
    rows = runtime._conn.execute(
        "SELECT role, content FROM raw_event WHERE role IN (?, ?)",
        (ROLE_PROSE, ROLE_ACTIONS),
    ).fetchall()
    by_role = {r["role"]: r["content"] for r in rows}

    assert by_role[ROLE_PROSE] == result.prose
    assert "：" in by_role[ROLE_ACTIONS], "动作序列该是逐行的 角色：动作"
    runtime.close()


def test_状态在场级就落库_不等到章末(tmp_path, bible):
    """下一场的演员开场要读上一场写的状态。攒到章末落，他们会拿着过期状态演。"""
    from ledger.types import ClockDomain, ClockStamp, StateReadRequest, StateSelector

    runtime, _, _ = _play(tmp_path, bible, beats_per_chapter=2)
    read = runtime.get_states(
        StateReadRequest(
            space_id=bible.space_id,
            owner_id="林晚",
            selectors=(StateSelector("current_goal"),),
            # 第 1 场之后、第 2 场之前。攒到章末的话这里读不到东西。
            at=ClockStamp(ClockDomain.STORY_TIME, story_time(1, 1)),
        )
    )
    assert "current_goal" in read.present
    assert read.present["current_goal"].payload["value"] == "把这次问出来的话走到底"
    runtime.close()


def test_多拍不会写出重复的世界记忆(tmp_path, bible):
    runtime, _, _ = _play(tmp_path, bible, name="dedup.db")
    bible_ = load_bible(BIBLE_PATH)
    world = _recall(
        runtime,
        bible_,
        WORLD_OWNER,
        "把事情摊开",
        chapter_end(1),
        kinds=frozenset({MemoryKind.EPISODE}),
        include_owners=(WORLD_OWNER,),
    )
    texts = [s.text for s in world.selected]
    assert len(texts) == len(set(texts))
    runtime.close()


# ---------------------------------------------------------------------------
# 宿主策略层的归一
# ---------------------------------------------------------------------------


def _policy_fixture(tmp_path):
    from crew import stage
    from crew.policy import HostPolicy

    book = Bible(
        space_id="novel:policy",
        title="归一",
        premise="测试",
        ending_direction="测试",
        total_chapters=1,
        characters=(
            CharacterSpec(WORLD_OWNER, "世界", ""),
            CharacterSpec("甲", "甲", "", initial_state={"location": "某处"}),
        ),
        state_fields=(
            FieldSeed("location"),
            FieldSeed("current_goal"),
            FieldSeed("status", kind="ENUM", allowed_values=("健康", "疲惫")),
            FieldSeed("随身物", kind="TEXT_LIST"),
        ),
    )
    runtime = MemoryRuntime(tmp_path / "policy.db")
    stage.build_space(runtime, book)
    return runtime, book, HostPolicy(runtime, book)


def _states_of(runtime, book, owner, at):
    from ledger.types import StateReadRequest, StateSelector

    read = runtime.get_states(
        StateReadRequest(
            space_id=book.space_id,
            owner_id=owner,
            selectors=tuple(
                StateSelector(s.field_id) for s in book.state_fields
            ),
            at=ClockStamp(ClockDomain.STORY_TIME, at),
        )
    )
    return {fid: item.payload.get("value") for fid, item in read.present.items()}


def test_数组字段收到单个字符串会被包成数组(tmp_path):
    """抽取器把 TEXT_LIST 填成字符串是常见错法，包一层是无歧义的修法。"""
    from crew.models import ChapterProposals, StateProposal

    runtime, book, policy = _policy_fixture(tmp_path)
    source = policy.capture_actions(1, 0, "甲把路引收进怀里。")
    committed, rejected = policy.commit_scene(
        1,
        0,
        ChapterProposals(states=(StateProposal("甲", "随身物", "路引"),)),
        source,
        run_id="ch1:b1",
    )
    assert committed == 1
    assert rejected == ()
    assert _states_of(runtime, book, "甲", chapter_end(1))["随身物"] == ["路引"]
    runtime.close()


def test_闭集外的值仍然被账本弹回(tmp_path):
    """归一只做无歧义的转换。枚举猜不出来，就该让它失败得明显。"""
    from crew.models import ChapterProposals, StateProposal

    runtime, book, policy = _policy_fixture(tmp_path)
    source = policy.capture_actions(1, 0, "甲站在门口。")
    committed, rejected = policy.commit_scene(
        1,
        0,
        ChapterProposals(states=(StateProposal("甲", "status", "目送别人离开"),)),
        source,
        run_id="ch1:b1",
    )
    assert committed == 0
    assert any("SCHEMA_MISMATCH" in r for r in rejected)
    runtime.close()


def test_占位词不会被当成事实落库(tmp_path):
    """真模型给 affiliation 写过"无"。这不是事实，只会污染检索。"""
    from crew.models import ChapterProposals, StateProposal

    runtime, book, policy = _policy_fixture(tmp_path)
    source = policy.capture_actions(1, 0, "甲什么也没说。")
    committed, rejected = policy.commit_scene(
        1,
        0,
        ChapterProposals(states=(StateProposal("甲", "current_goal", "未知"),)),
        source,
        run_id="ch1:b1",
    )
    assert committed == 0
    assert any("占位词" in r for r in rejected)
    assert "current_goal" not in _states_of(runtime, book, "甲", chapter_end(1))
    runtime.close()


# ---------------------------------------------------------------------------
# fail-closed
# ---------------------------------------------------------------------------


def test_必带位置缺失时导演阻断而不是硬演(tmp_path):
    """没有 location 的角色不该被推上场——宁可报错也不让它猜自己在哪。"""
    from crew import stage

    ghost = Bible(
        space_id="novel:ghost",
        title="缺状态的书",
        premise="测试",
        ending_direction="测试",
        total_chapters=1,
        characters=(
            CharacterSpec(WORLD_OWNER, "世界", ""),
            CharacterSpec("无名者", "没有初始状态", "", initial_state={}),
        ),
        state_fields=(
            FieldSeed("location"),
            FieldSeed("current_goal"),
            FieldSeed("status", kind="ENUM", allowed_values=("健康",)),
        ),
    )
    runtime = MemoryRuntime(tmp_path / "ghost.db")
    stage.build_space(runtime, ghost)
    director = Director(
        runtime, build_fake_provider(ghost.playable_names, 1), ghost
    )

    with pytest.raises(SceneBlocked) as blocked:
        director.build_scene(
            Beat("ch1:b1", 1, 0, "开场", ("无名者",), "某处")
        )
    assert blocked.value.owner_id == "无名者"
    assert "location" in str(blocked.value)
    runtime.close()


# ---------------------------------------------------------------------------
# Bible 校验
# ---------------------------------------------------------------------------


def test_林晚设定能通过校验(bible):
    assert bible.playable_names == ("林晚", "沈砚", "周娘子")
    assert WORLD_OWNER in bible.cast_names
    assert WORLD_OWNER not in bible.playable_names


def test_闭集字段的初始值必须在闭集内(tmp_path):
    path = tmp_path / "bad.yaml"
    path.write_text(
        """
space_id: "novel:bad"
title: "坏设定"
premise: "x"
ending_direction: "y"
state_fields:
  - field_id: location
    kind: TEXT
  - field_id: current_goal
    kind: TEXT
  - field_id: status
    kind: ENUM
    allowed_values: ["健康"]
characters:
  - owner_id: "世界"
    persona: "世界"
  - owner_id: "甲"
    persona: "甲"
    initial_state:
      location: "某处"
      status: "半死不活"
""",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="不在闭集"):
        load_bible(path)


def test_缺_location_初始值的角色在读取设定时就报错(tmp_path):
    path = tmp_path / "noloc.yaml"
    path.write_text(
        """
space_id: "novel:noloc"
title: "缺位置"
premise: "x"
ending_direction: "y"
state_fields:
  - field_id: location
    kind: TEXT
  - field_id: current_goal
    kind: TEXT
  - field_id: status
    kind: ENUM
    allowed_values: ["健康"]
characters:
  - owner_id: "世界"
    persona: "世界"
  - owner_id: "甲"
    persona: "甲"
""",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="缺 location"):
        load_bible(path)


def test_没有世界仓的设定被拒(tmp_path):
    path = tmp_path / "noworld.yaml"
    path.write_text(
        """
space_id: "novel:noworld"
title: "没有世界仓"
premise: "x"
ending_direction: "y"
state_fields:
  - field_id: location
    kind: TEXT
  - field_id: current_goal
    kind: TEXT
  - field_id: status
    kind: ENUM
    allowed_values: ["健康"]
characters:
  - owner_id: "甲"
    persona: "甲"
    initial_state:
      location: "某处"
""",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="世界仓"):
        load_bible(path)
