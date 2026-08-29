"""成文后审校的验收（离线）。

验的是"审校这道网张开了没有"，不是"审得准不准"。审得准只有真模型能验。
"""

from __future__ import annotations

import re

import pytest

from crew.bible_io import load_bible
from crew.context import SceneAnchors
from crew.models import ActionProposal, Bible, NarratableScene, ReviewIssue
from crew.narrator import Narrator, render_issues
from crew.reflect import accept_revision, reflect_review
from crew.reviewer import Reviewer, parse_critique
from crew.studio import Studio, StudioConfig
from ledger.runtime import MemoryRuntime
from llm.fake import build_fake_provider
from llm.provider import Role

BIBLE_PATH = "bible/linwan.yaml"


@pytest.fixture
def bible() -> Bible:
    return load_bible(BIBLE_PATH)


def _scenes() -> tuple[NarratableScene, ...]:
    return (
        NarratableScene(
            location="临安",
            cast=("林晚", "周娘子"),
            goal="把话说开",
            actions=(ActionProposal("林晚", "她把信推过去", claimed_location="临安"),),
        ),
    )


# ---------------------------------------------------------------------------
# 解析
# ---------------------------------------------------------------------------


def test_从审查过程末尾抠出硬伤清单():
    """审校要先用自然语言写审查过程，清单在最后。前面的散文不能干扰解析。"""
    raw = (
        "锚点：林晚此刻在临安，周娘子在绣坊。\n"
        "逐段核对：第二段写她走出城门，动作序列里没有这一步。\n"
        '{"issues":[{"quote":"她走出城门","why":"动作序列没有出城","fix":"删掉这句"}]}'
    )
    result = parse_critique(raw)
    assert result.parsed
    assert not result.ok
    assert result.issues[0].quote == "她走出城门"
    assert result.issues[0].fix == "删掉这句"


def test_审不出时按通过兜底而不是误判有硬伤():
    """解析失败不该崩，也不该当成"有问题"——那会触发一次没必要的修订。"""
    result = parse_critique("这段写得挺好的，没什么问题。")
    assert not result.parsed
    assert result.ok


def test_被截断的审查过程解析不出来():
    """真模型上撞过：审查清单写到一半耗尽 max_tokens，末尾的 JSON 根本没写出来。

    这时必须是 parsed=False。它跟"审完了确实干净"看着效果一样（都放过），
    但 parsed 这一位是唯一的区别信号，报出来才知道是预算不够而不是稿子干净。
    """
    result = parse_critique("锚点：地点=临安\n段1：她抬脚踏上石阶，三级台阶，动作")
    assert not result.parsed


def test_空产出解析不出来():
    """思考型模型可能把预算全花在 reasoning 上，正文一个字不吐。"""
    assert not parse_critique("").parsed


def test_空对象不污染硬伤清单():
    result = parse_critique('{"issues":[{},{"quote":"","why":""},{"why":"死人开口了"}]}')
    assert result.parsed
    assert len(result.issues) == 1


def test_取最后一个_json_块():
    """审查过程里若不慎写了花括号，以末尾那个为准。"""
    raw = '前面提到 {"issues":[{"why":"这是举例不算数"}]}\n' '结论：{"issues":[]}'
    assert parse_critique(raw).ok


# ---------------------------------------------------------------------------
# 防截断护栏
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "prev,nxt,expected",
    [
        ("一二三四五六七八九十", "一二三四五六七八九零", True),
        ("一二三四五六七八九十", "", False),
        ("一二三四五六七八九十", "一二三四五六七八九十", False),
        ("一二三四五六七八九十", "一二三", False),
        ("", "新写的", True),
    ],
)
def test_防截断护栏(prev, nxt, expected):
    """定向修订本该与原文长度相当。骤缩多半是截断或答非所问，宁可弃用。"""
    assert accept_revision(prev, nxt) is expected


# ---------------------------------------------------------------------------
# 反射循环
# ---------------------------------------------------------------------------


class _StubReviewer:
    def __init__(self, script: list) -> None:
        self.script = list(script)
        self.calls = 0

    def critique(self, prose, scenes, anchors=None, goal=""):
        self.calls += 1
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class _StubNarrator:
    def __init__(self, script: list) -> None:
        self.script = list(script)
        self.calls = 0
        self.saw_anchors: list = []

    def revise(self, prose, issues, anchors=None):
        self.calls += 1
        self.saw_anchors.append(anchors)
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def _critique(*issues, parsed=True):
    from crew.models import CritiqueResult

    return CritiqueResult(tuple(issues), parsed=parsed)


def _issue(why="有硬伤"):
    return ReviewIssue("原句", why, "改法")


def test_一轮审出硬伤_改完复审通过():
    reviewer = _StubReviewer([_critique(_issue()), _critique()])
    narrator = _StubNarrator(["改好之后的正文，长度和原来差不多，够长了。"])
    result = reflect_review(
        reviewer, narrator, "原始正文，长度和改后差不多。", _scenes()
    )
    assert result.passed
    assert result.prose.startswith("改好之后")
    assert [r.issue_count for r in result.rounds] == [1, 0]
    assert result.rounds[0].revised


def test_首轮无硬伤就早退_不白花一次修订():
    reviewer = _StubReviewer([_critique()])
    narrator = _StubNarrator([])
    result = reflect_review(reviewer, narrator, "干净的正文。", _scenes())
    assert result.passed
    assert narrator.calls == 0
    assert reviewer.calls == 1


def test_修订稿被截断就弃用并保留原稿():
    reviewer = _StubReviewer([_critique(_issue())])
    narrator = _StubNarrator(["短"])
    original = "原始正文，写得挺长的一段，至少比那个字长得多。"
    result = reflect_review(reviewer, narrator, original, _scenes())
    assert result.prose == original
    assert not result.passed
    assert not result.rounds[0].revised


def test_审校抛异常不拖垮整章():
    """一次超时不该让这一章没有正文。保留当前稿收手。"""
    reviewer = _StubReviewer([TimeoutError("读超时\n第二行不该出现")])
    narrator = _StubNarrator([])
    result = reflect_review(reviewer, narrator, "原稿。", _scenes())
    assert result.prose == "原稿。"
    assert result.rounds[0].error == "读超时"
    assert not result.passed


def test_修订抛异常时保留此前已采纳的修订():
    reviewer = _StubReviewer([_critique(_issue()), _critique(_issue())])
    narrator = _StubNarrator(
        ["第一轮改好的正文，长度够。", RuntimeError("限流")]
    )
    result = reflect_review(
        reviewer, narrator, "原始正文，长度够。", _scenes(), max_rounds=3
    )
    assert result.prose.startswith("第一轮改好")
    assert result.rounds[-1].error == "限流"


def test_轮数设零就完全跳过审校():
    reviewer = _StubReviewer([])
    narrator = _StubNarrator([])
    result = reflect_review(
        reviewer, narrator, "原稿。", _scenes(), max_rounds=0
    )
    assert result.prose == "原稿。"
    assert reviewer.calls == 0


def test_用满轮数仍有硬伤则_passed_为假():
    reviewer = _StubReviewer([_critique(_issue()), _critique(_issue())])
    narrator = _StubNarrator(["第一次改，长度够长。", "第二次改，长度也够长。"])
    result = reflect_review(
        reviewer, narrator, "原始正文，长度够长。", _scenes(), max_rounds=2
    )
    assert not result.passed
    assert len(result.rounds) == 2


# ---------------------------------------------------------------------------
# 锚点渲染
# ---------------------------------------------------------------------------


def test_修订时锚点要标明勿抄入正文():
    """不标的话，执笔人会把锚点里提到、正文原本没有的道具搬进正文。"""
    anchors = SceneAnchors(prop_ledger="- 林晚 的随身物：玉璧")
    assert "勿抄入正文" in anchors.render(for_revision=True)
    assert "勿抄入正文" not in anchors.render()


def test_空锚点不渲染出空标题():
    assert SceneAnchors().render() == ""
    assert not SceneAnchors()


def test_硬伤清单渲染带原句和改法():
    text = render_issues((ReviewIssue("她走出城门", "序列里没有", "删掉"),))
    assert "她走出城门" in text
    assert "序列里没有" in text
    assert "删掉" in text


# ---------------------------------------------------------------------------
# 接进管线
# ---------------------------------------------------------------------------


def _studio(tmp_path, bible, provider, name, **overrides):
    runtime = MemoryRuntime(tmp_path / name)
    config = dict(
        beats_per_chapter=1,
        chars_per_action=80,
        min_chapter_length=150,
        max_replans=0,
    )
    config.update(overrides)
    return runtime, Studio(
        runtime=runtime, llm=provider, bible=bible, config=StudioConfig(**config)
    )


def test_落库的是审过的稿而不是初稿(tmp_path, bible):
    """审校排在 capture 之前。先落库再审校，等于把硬伤写进下一章的承接锚点。"""
    from crew.policy import ROLE_PROSE

    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    provider.override(
        Role.REVIEWER,
        lambda s, u: '核对完毕。{"issues":[{"quote":"雨","why":"序列里没有雨","fix":"删"}]}',
    )

    runtime, studio = _studio(
        tmp_path, bible, provider, "reviewed.db", max_review_rounds=1
    )
    result = studio.write_chapter(1, studio.prepare())

    assert "已按审校意见改过" in result.prose
    # 账本里的正文必须是同一版。
    rows = runtime.recent_raw_events(bible.space_id, "世界", limit=1, role=ROLE_PROSE)
    assert "已按审校意见改过" in rows[0]["content"]
    runtime.close()


def test_审校拿到的依据跟叙述者拿到的是同一份(tmp_path, bible):
    """审的正是"叙述者有没有超出它拿到的依据"。两边各渲染一份的话，审校会把
    叙述者本来有据可依的地方报成硬伤，或者反过来漏掉真的加戏。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    runtime, studio = _studio(
        tmp_path, bible, provider, "sameinput.db", beats_per_chapter=2,
        max_review_rounds=1,
    )
    studio.write_chapter(1, studio.prepare())

    narrate = provider.transcript.by_role(Role.NARRATOR)[0].user
    review = provider.transcript.by_role(Role.REVIEWER)[0].user
    blocks = re.findall(r"── 第 \d+ 场：[^\n]+", narrate)
    assert len(blocks) == 2, "叙述者没拿到两场"
    for block in blocks:
        assert block in review, f"审校缺了这一场的依据：{block}"
    runtime.close()


def test_审校知道换地点是正常的(tmp_path, bible):
    """只给一个地点，它会把第二场整场报成"人物凭空移动"。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    runtime, studio = _studio(
        tmp_path, bible, provider, "multiloc.db", beats_per_chapter=2,
        max_review_rounds=1,
    )
    studio.write_chapter(1, studio.prepare())

    review = provider.transcript.by_role(Role.REVIEWER)[0].user
    assert "本章分 2 场" in review
    assert "换地点、换时间是正常的" in review
    runtime.close()


def test_审校审的是整章而不是每场(tmp_path, bible):
    """章级成文只落一次笔，所以审校也只审一遍——一章两场审两次的话，
    第二次看不见第一次已经改过什么。"""
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    runtime, studio = _studio(
        tmp_path, bible, provider, "chapterwide.db", beats_per_chapter=2,
        max_review_rounds=1,
    )
    result = studio.write_chapter(1, studio.prepare())

    assert len(result.scenes) == 2
    assert len(provider.transcript.by_role(Role.REVIEWER)) == 1
    assert len(result.review_rounds) == 1
    runtime.close()


def test_审校轮数设零时管线不调审校(tmp_path, bible):
    provider = build_fake_provider(bible.playable_names, bible.total_chapters)
    runtime, studio = _studio(
        tmp_path, bible, provider, "noreview.db", max_review_rounds=0
    )
    studio.write_chapter(1, studio.prepare())
    assert provider.transcript.by_role(Role.REVIEWER) == []
    runtime.close()
