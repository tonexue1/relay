"""对照锚点、风味卡、离线计分卡。"""

from __future__ import annotations

import dataclasses

import pytest

import verify as verifier
from crew import clock
from crew.bible_io import load_bible
from crew.context import TAIL_CHARS, AnchorBuilder
from crew.models import Bible, StateChange, WORLD_OWNER
from crew.policy import HostPolicy, ROLE_PROSE
from crew.stage import build_space
from crew.style import available_cards, load_style_card
from ledger.runtime import MemoryRuntime
from ledger.types import ClockDomain, RawEventDraft
from verify import ContinuityHit

BIBLE_PATH = "bible/linwan.yaml"


@pytest.fixture
def bible() -> Bible:
    return load_bible(BIBLE_PATH)


@pytest.fixture
def runtime(tmp_path, bible) -> MemoryRuntime:
    rt = MemoryRuntime(tmp_path / "anchors.db")
    build_space(rt, bible)
    yield rt
    rt.close()


def _mark_missing(
    runtime: MemoryRuntime, bible: Bible, owner: str, chapter: int
) -> Bible:
    """从第 chapter 章起把某人置为失踪，返回认这个取值为"已故"的设定副本。

    走宿主裁决的正常写入路径，而不是手搓账本命令——测的是真管线会产生的状态。
    """
    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(chapter, 0, f"{owner}失了踪。")
    policy.apply_state_changes(
        chapter,
        0,
        (StateChange(owner, "status", "失踪"),),
        source,
        run_id=f"missing:{owner}:{chapter}",
    )
    return dataclasses.replace(bible, death_states=("失踪",))


# ---------------------------------------------------------------------------
# 锚点
# ---------------------------------------------------------------------------


def _capture(runtime: MemoryRuntime, bible: Bible, at: int, text: str) -> None:
    runtime.capture(
        RawEventDraft(
            space_id=bible.space_id,
            owner_id=WORLD_OWNER,
            role=ROLE_PROSE,
            content=text,
            clock_domain=ClockDomain.STORY_TIME,
            occurred_at=at,
        )
    )


def test_承接读的是上一章的逐字原文而不是摘要(runtime, bible):
    """摘要丢掉的正是"上一段最后怎么收的"，承接语气就靠这个。"""
    _capture(runtime, bible, clock.chapter_end(1), "她把门带上。门轴响了一声。")
    anchors = AnchorBuilder(runtime, bible).build(2)
    assert anchors.previous_tail.endswith("门轴响了一声。")


def test_承接只取尾部不把整章塞回去(runtime, bible):
    """整章喂回去会诱使叙述者复述。只要尾巴。"""
    _capture(runtime, bible, clock.chapter_end(1), "甲" * (TAIL_CHARS * 2) + "结尾。")
    anchors = AnchorBuilder(runtime, bible).build(2)
    assert len(anchors.previous_tail) == TAIL_CHARS
    assert anchors.previous_tail.endswith("结尾。")


def test_第一章没有上一章可承接(runtime, bible):
    assert AnchorBuilder(runtime, bible).build(1).previous_tail == ""


def test_承接不会读到未来的正文(runtime, bible):
    """点时读取：第 1 章看不到第 2 章。摊平的当前状态做不到这一点。"""
    _capture(runtime, bible, clock.chapter_end(2), "第二章的正文。")
    assert AnchorBuilder(runtime, bible).build(2).previous_tail == ""


def test_承接读正文不读动作序列(runtime, bible):
    """两类原文同存在世界仓下。不按 role 筛就会拿一串
    "林晚：把针搁回绣绷" 去当上一章的语气承接。"""
    policy = HostPolicy(runtime, bible)
    policy.capture_chapter_prose(1, "她把门带上。门轴响了一声。")
    # 动作序列晚于正文落库，倒序取第一条会先撞上它。
    policy.capture_actions(1, 9, "林晚：把针搁回绣绷，情绪：克制")
    tail = AnchorBuilder(runtime, bible).build(2).previous_tail
    assert tail.endswith("门轴响了一声。")
    assert "绣绷" not in tail


def test_道具账本按人渲染随身物(runtime, bible):
    ledger = AnchorBuilder(runtime, bible).build(1, 0).prop_ledger
    assert "林晚 的随身物" in ledger
    assert "三十六封信的副本" in ledger


def test_所在地锚点覆盖所有可上场角色(runtime, bible):
    where = AnchorBuilder(runtime, bible).build(1, 0).whereabouts
    assert "林晚：临安" in where
    assert "沈砚：洛阳" in where


def test_没声明_death_states_就不渲染名册(runtime, bible):
    assert AnchorBuilder(runtime, bible).build(1, 0).dead_roster == ""


def test_声明了_death_states_的角色进名册(runtime, bible):
    dead = _mark_missing(runtime, bible, "沈砚", chapter=1)
    roster = AnchorBuilder(runtime, dead).build(2, 0).dead_roster
    assert "沈砚" in roster and "失踪" in roster


def test_名册是点时的_死之前那几章不该进名册(runtime, bible):
    """账本答得上"第 1 章那会儿他还活着"，摊平的当前状态答不上。"""
    dead = _mark_missing(runtime, bible, "沈砚", chapter=2)
    builder = AnchorBuilder(runtime, dead)
    assert "沈砚" not in builder.build(1, 0).dead_roster
    assert "沈砚" in builder.build(3, 0).dead_roster


# ---------------------------------------------------------------------------
# 闭集地点：过细的答案归一到候选值
# ---------------------------------------------------------------------------


def test_过细的地点被归一到闭集候选值(runtime, bible):
    """真模型写出过"洛阳·府衙外·石阶"和"门内"。前者只含一个候选值，能救；
    后者一个都不含，让账本弹回去。"""
    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(1, 0, "她上了石阶。")
    written, _, rejected = policy.apply_state_changes(
        1,
        0,
        (
            StateChange("林晚", "location", "洛阳·府衙外·石阶"),
            StateChange("沈砚", "location", "门内"),
        ),
        source,
        run_id="coerce",
    )
    assert written == 1
    assert any("门内" in note or "SCHEMA" in note for note in rejected)
    where = AnchorBuilder(runtime, bible).build(2, 0).whereabouts
    assert "林晚：洛阳" in where


def test_歧义的地点不猜(runtime, bible):
    """两个候选值都是子串就是真有歧义，宁可让账本弹回去。"""
    policy = HostPolicy(runtime, bible)
    assert policy._coerce("location", "从临安到洛阳的路上") == "从临安到洛阳的路上"


def test_已经是候选值的地点原样通过(runtime, bible):
    policy = HostPolicy(runtime, bible)
    assert policy._coerce("location", "洛阳") == "洛阳"


def test_数组字段收到单字符串仍然被包成数组(runtime, bible):
    policy = HostPolicy(runtime, bible)
    assert policy._coerce("随身物", "一只旧绣绷") == ["一只旧绣绷"]


# ---------------------------------------------------------------------------
# 抽取器无权写的字段
# ---------------------------------------------------------------------------


def test_抽取器写不了地点(runtime, bible):
    """真模型上撞过：最后一场没人移动、质检没提地点，抽取器就自作主张把两个人
    都写回了临安，其中沈砚根本没离开过洛阳。地点的权威是质检，不是抽取器。"""
    from crew.models import ChapterProposals, StateProposal

    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(1, 0, "门开着。")
    committed, rejected = policy.commit_scene(
        1,
        0,
        ChapterProposals(states=(StateProposal("沈砚", "location", "临安"),)),
        source,
        run_id="locked",
    )
    assert committed == 0
    assert any("抽取器无权写" in note for note in rejected)
    assert AnchorBuilder(runtime, bible).build(2, 0).whereabouts.count("沈砚：洛阳") == 1


def test_质检仍然写得了地点(runtime, bible):
    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(1, 0, "她上路了。")
    written, _, _ = policy.apply_state_changes(
        1, 0, (StateChange("林晚", "location", "途中"),), source, run_id="vetted"
    )
    assert written == 1
    assert "林晚：途中" in AnchorBuilder(runtime, bible).build(2, 0).whereabouts


def test_没上锁的字段抽取器照样能写(runtime, bible):
    from crew.models import ChapterProposals, StateProposal

    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(1, 0, "她累了。")
    committed, _ = policy.commit_scene(
        1,
        0,
        ChapterProposals(states=(StateProposal("林晚", "status", "疲惫"),)),
        source,
        run_id="unlocked",
    )
    assert committed == 1


def test_上锁字段不出现在抽取器的字段清单里(bible):
    """列了它就会填，填了又被挡回来，白花 token 还制造噪音拒绝。"""
    from crew.chronicler import Chronicler
    from llm.fake import build_fake_provider

    chronicler = Chronicler(
        build_fake_provider(bible.playable_names, bible.total_chapters), bible
    )
    assert "location" not in chronicler._fields
    assert "status" in chronicler._fields


# ---------------------------------------------------------------------------
# 风味卡
# ---------------------------------------------------------------------------


def test_内置风味卡都能加载():
    cards = available_cards()
    assert "baimiao" in cards and "gulong" in cards
    for card_id in cards:
        card = load_style_card(card_id)
        assert card.label and card.tagline and card.rhythm


def test_拼错卡名报错时列出可用的():
    with pytest.raises(ValueError, match="baimiao"):
        load_style_card("meiyouzhezhangka")


def test_三层注入各拿各的面():
    """规划只要钩子和场面，导演只要调度，叙述要全部。喂多了会带偏注意力。"""
    card = load_style_card("gulong")
    planner = card.for_planner()
    director = card.for_director()
    narrator = card.for_narrator()

    assert card.hook in planner
    assert card.rhythm not in planner
    assert card.direction_scene in director
    assert card.lexicon not in director
    assert card.rhythm in narrator and card.lexicon in narrator


def test_强度只改遵循力度不改卡的内容():
    card = load_style_card("baimiao")
    light = card.for_narrator("light")
    strong = card.for_narrator("strong")
    assert card.rhythm in light and card.rhythm in strong
    assert light != strong


def test_开篇运镜只在第一章给():
    card = load_style_card("gulong")
    assert card.direction_opening not in card.for_narrator()
    assert card.direction_opening in card.for_narrator(opening=True)


def _bible_variant(tmp_path, name: str, old: str, new: str):
    text = open(BIBLE_PATH, encoding="utf-8").read().replace(old, new)
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def test_风味卡拼错时读设定就炸(tmp_path):
    """别等到第一次落笔才发现卡名打错了。"""
    path = _bible_variant(
        tmp_path, "badcard.yaml", "style_card: baimiao", "style_card: buchunzai"
    )
    with pytest.raises(ValueError, match="没有风味卡"):
        load_bible(path)


def test_death_states_不在闭集里就炸(tmp_path):
    """写了永远命中不了的取值，名册会一直空着却没人发现。"""
    path = _bible_variant(
        tmp_path, "baddeath.yaml", "death_states: []", 'death_states: ["羽化"]'
    )
    with pytest.raises(ValueError, match="不在 status 闭集"):
        load_bible(path)


def test_强度档位写错也炸(tmp_path):
    path = _bible_variant(
        tmp_path, "badint.yaml", "style_intensity: medium", "style_intensity: 猛"
    )
    with pytest.raises(ValueError, match="style_intensity"):
        load_bible(path)


# ---------------------------------------------------------------------------
# 计分卡
# ---------------------------------------------------------------------------


def test_标题撞车被抓出来():
    dupes = verifier.find_duplicate_titles(
        ((1, "对坐"), (2, "夜行"), (3, "对　坐"), (4, "夜行"))
    )
    assert {n for n, _, _ in dupes} == {3, 4}


def test_风味词优先用卡自己声明的():
    """从流畅句子里抽词只能抽出"偏爱苍茫"这种半截词，所以卡要自己报准数。"""
    card = load_style_card("baimiao")
    assert card.lexicon_terms
    assert verifier.lexicon_terms(card) == card.lexicon_terms


def test_没声明风味词才退回抽词():
    card = dataclasses.replace(
        load_style_card("baimiao"),
        lexicon_terms=(),
        lexicon="偏爱、苍茫、亘古、纪元，点到、即止",
    )
    terms = verifier.lexicon_terms(card)
    assert "苍茫" in terms and "亘古" in terms
    assert "偏爱" not in terms and "即止" not in terms


def test_世界标志词不拿成句规则来数(bible):
    """整句"临安到洛阳需走一个多月"拿去 in 判断永远不命中，切碎又全是噪音。"""
    entries = verifier.world_entries(bible)
    assert "临安" in entries and "洛阳" in entries
    assert not any(len(entry) > 12 for entry in entries)


def test_短句占比():
    assert verifier.short_sentence_ratio("短。短。" + "长" * 30 + "。") == pytest.approx(2 / 3)


def test_套话命中计数():
    hits = dict(verifier.count_generic("他不禁苦涩地笑了笑，不禁又叹了口气。"))
    assert hits["不禁"] == 2


def test_词表覆盖率数单字词():
    """白描卡的风味词整个是"手""门""针"这样的单字。
    先分词再匹配的话，长度不足 2 的片段会被丢掉，单字词一个都留不下。"""
    cov = verifier.term_coverage("她把手按在门上。", ("手", "门", "刀"))
    assert cov.hit == 2
    assert cov.missed == ("刀",)


def test_词表去重后再计数():
    cov = verifier.term_coverage("门开了。", ("门", "门", "窗"))
    assert cov.total == 2 and cov.hit == 1


def test_单字风味词能被计分卡数到(bible):
    card = verifier.grade(
        bible, ((1, "她把手按在门板上，针搁回绣绷。"),), (), load_style_card("baimiao")
    )
    assert card.lexicon.hit >= 3
    assert card.scores["风味贴合"] > 0


def test_死人开口被计分卡抓出来(runtime, bible):
    dead = _mark_missing(runtime, bible, "沈砚", chapter=1)
    hits = verifier.check_continuity(runtime, dead, ((2, "沈砚推门进来，坐下了。"),))
    assert any(h.kind == "死人开口" for h in hits)


def test_死之前的章节不报死人开口(runtime, bible):
    dead = _mark_missing(runtime, bible, "沈砚", chapter=3)
    hits = verifier.check_continuity(runtime, dead, ((1, "沈砚推门进来，坐下了。"),))
    assert not [h for h in hits if h.kind == "死人开口"]


def test_隔空同框被抓出来(runtime, bible):
    """林晚在临安、沈砚在洛阳，从章首到章末都没动，却同现一章。"""
    hits = verifier.check_continuity(runtime, bible, ((1, "林晚看着沈砚，什么也没说。"),))
    assert any(h.kind == "隔空同框" for h in hits)


def test_同一章里位置一致就不报(runtime, bible):
    hits = verifier.check_continuity(runtime, bible, ((1, "林晚和周娘子对坐。"),))
    assert not [h for h in hits if h.kind == "隔空同框"]


def test_对白里提到的人不算在场(runtime, bible):
    """真产出上撞过：周娘子说「那沈砚要是真心」，沈砚人在洛阳，
    按名字直接匹配就报一条隔空同框，其实他只是被人提起。"""
    prose = "周娘子按住包袱。「那沈砚要是真心，早该有信儿了。」林晚没说话。"
    hits = verifier.check_continuity(runtime, bible, ((1, prose),))
    assert not [h for h in hits if h.kind == "隔空同框"]


def test_死者只是被提起不算死人开口(runtime, bible):
    dead = _mark_missing(runtime, bible, "沈砚", chapter=1)
    prose = "周娘子叹了口气。「沈砚早就没了。」林晚没接话。"
    hits = verifier.check_continuity(runtime, dead, ((2, prose),))
    assert not [h for h in hits if h.kind == "死人开口"]


def test_本章赶路汇合不算隔空同框(runtime, bible):
    """章首两地正是这一章要写的事。只有章首章末都两地才算硬伤。"""
    policy = HostPolicy(runtime, bible)
    source = policy.capture_actions(1, 0, "林晚到了洛阳。")
    policy.apply_state_changes(
        1, 0, (StateChange("林晚", "location", "洛阳"),), source, run_id="travel"
    )
    hits = verifier.check_continuity(runtime, bible, ((1, "林晚看着沈砚。"),))
    assert not [h for h in hits if h.kind == "隔空同框"]


def test_剥对白不影响原文里的道具检查(runtime, bible):
    """道具串门看的是"这件东西被写进了正文"，写在对白里也算写了。"""
    assert verifier.strip_dialogue("他说「你好」，然后走了。") == "他说，然后走了。"


def test_连贯性命中会拉低总分(bible):
    style = load_style_card("baimiao")
    proses = ((1, "林晚在临安绣坊。"),)
    titles = ((1, "对坐"),)
    clean = verifier.grade(bible, proses, titles, style)
    dirty = verifier.grade(
        bible, proses, titles, style, continuity=(ContinuityHit(1, "死人开口", "沈砚"),)
    )
    assert dirty.overall < clean.overall
    assert dirty.scores["账本连贯"] < 1.0


def test_报告能渲染出来(bible):
    card = verifier.grade(bible, ((1, "林晚在临安绣坊里坐着。"),), ((1, "对坐"),))
    text = verifier.render_report(card)
    assert "计分卡" in text and "总分" in text
