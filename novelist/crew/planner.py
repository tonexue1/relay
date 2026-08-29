"""规划：粗纲冻结一次，细纲滚动重规划。

分两层是因为单层大纲撑不住长篇（DOC），而一次冻死全书细纲又接不住
生成过程本身的不确定性（DOME）。所以粗纲定方向，细纲只看未来一两章。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

from .models import Act, Beat, Bible, RoughOutline

_ROUGH_SYSTEM = """你是长篇小说的结构规划者。你只输出结构，不写正文。
要求：
1. 幕之间必须有不可逆的转折，不能靠"又发生了一件事"推进。
2. 结局方向已经由作者给定，你的幕结构必须真的走向它。
3. 覆盖全部章节，章号区间不重叠、不留空。
只输出 JSON。"""

_ROUGH_USER = """《{title}》共 {total} 章。

前提：{premise}
结局方向：{ending}
{style}世界规则：
{rules}
禁忌（绝对不能出现）：
{taboos}
角色：
{characters}

输出 JSON：
{{
  "main_conflict": "一句话说清主线冲突是谁跟谁、争什么",
  "ending": "结局落点，必须与作者给的方向一致",
  "acts": [
    {{"name": "幕名", "summary": "这一幕发生什么、结束时局势变成什么样",
      "chapter_from": 1, "chapter_to": 2}}
  ]
}}"""

_BEAT_SYSTEM = """你是导演组的场次规划者。你把一章拆成若干 beat，不写正文。
要求：
1. `done_when` 只写一件可观察的状态变化：谁到了哪、谁知道了什么、谁做了什么决定。
   一句话，一个变化。
2. `done_when` 绝对不许写成台词清单或道具清单。
   ✗ "周娘子说'走吧我不拦你'，并交出玉璧，林晚磕头"
   ✓ "林晚已经离开绣坊，周娘子没有拦"
   写死具体台词和道具，等于要求演员逐字复述，这个 beat 永远判不完成。
3. `must_happen` 最多 2 条，写必须落地的事实，别写"气氛紧张"。
4. `cast` 只能用给定的角色名。地点只能是一个。
5. 不要重复已经发生过的事。
只输出 JSON。"""

_BEAT_USER = """《{title}》，正在规划第 {chapter} 章。

主线冲突：{conflict}
本章所属幕：{act}
可用角色：{cast}
{style}禁忌：
{taboos}

已发生（世界视角，按章序）：
{history}

各角色此刻状态：
{states}

为第 {chapter} 章规划 {count} 个 beat。输出 JSON：
{{
  "beats": [
    {{"goal": "本 beat 要达成什么",
      "cast": ["角色名"],
      "location": "地点",
      "must_happen": ["必须落地的具体事实，最多 2 条"],
      "done_when": "一件可观察的状态变化"}}
  ]
}}"""

_REPLAN_SYSTEM = """你是导演组的救火者。一个 beat 卡住了，你要换一个目标让故事动起来。
新目标必须：不重复卡住的那个；能被本场在场角色单独推动；不违反禁忌。
`done_when` 只写一件可观察的状态变化，不许写成台词或道具清单。
只输出 JSON。"""

_REPLAN_USER = """《{title}》第 {chapter} 章卡住了。

卡住的 beat 目标：{goal}
卡住原因：{reason}
在场角色：{cast}
地点：{location}
主线冲突：{conflict}

换一个目标。输出 JSON：
{{
  "goal": "新目标",
  "must_happen": ["必须落地的具体事实"],
  "done_when": "完成判据"
}}"""


def _bullets(items: tuple[str, ...] | list[str]) -> str:
    return "\n".join(f"- {item}" for item in items) or "-（无）"


class Planner:
    def __init__(self, llm: LLMProvider, style_brief: str = "") -> None:
        self._llm = llm
        self._style = f"{style_brief}\n\n" if style_brief else ""

    def rough_outline(self, bible: Bible) -> RoughOutline:
        characters = "\n".join(
            f"- {c.owner_id}：{c.persona}"
            for c in bible.characters
            if c.owner_id in bible.playable_names
        )
        data = self._llm.complete_json(
            Role.PLANNER,
            _ROUGH_SYSTEM,
            _ROUGH_USER.format(
                title=bible.title,
                total=bible.total_chapters,
                premise=bible.premise,
                ending=bible.ending_direction,
                style=self._style,
                rules=_bullets(bible.world_rules),
                taboos=_bullets(bible.taboos),
                characters=characters,
            ),
            temperature=0.7,
        )
        acts = tuple(
            Act(
                name=a.get("name", f"第{index + 1}幕"),
                summary=a.get("summary", ""),
                chapter_from=int(a.get("chapter_from", 1)),
                chapter_to=int(a.get("chapter_to", bible.total_chapters)),
            )
            for index, a in enumerate(data.get("acts", []))
        )
        return RoughOutline(
            main_conflict=data.get("main_conflict", ""),
            ending=data.get("ending", bible.ending_direction),
            acts=acts,
        )

    def next_beats(
        self,
        bible: Bible,
        outline: RoughOutline,
        chapter: int,
        *,
        history: tuple[str, ...],
        states: str,
        count: int = 2,
    ) -> tuple[Beat, ...]:
        act = outline.act_for(chapter)
        data = self._llm.complete_json(
            Role.PLANNER,
            _BEAT_SYSTEM,
            _BEAT_USER.format(
                title=bible.title,
                chapter=chapter,
                conflict=outline.main_conflict,
                act=f"{act.name}：{act.summary}" if act else "（未划入任何幕）",
                cast="、".join(bible.playable_names),
                style=self._style,
                taboos=_bullets(bible.taboos),
                history=_bullets(history),
                states=states or "-（无）",
                count=count,
            ),
            temperature=0.7,
        )
        beats = []
        known = set(bible.playable_names)
        for index, raw in enumerate(data.get("beats", [])):
            cast = tuple(c for c in raw.get("cast", []) if c in known)
            if not cast:
                continue
            beats.append(
                Beat(
                    beat_id=f"ch{chapter}:b{index + 1}",
                    chapter=chapter,
                    scene_index=len(beats),
                    goal=raw.get("goal", ""),
                    cast=cast,
                    location=raw.get("location", ""),
                    must_happen=tuple(raw.get("must_happen", [])),
                    done_when=raw.get("done_when", ""),
                )
            )
        return tuple(beats)

    def replan(
        self,
        bible: Bible,
        outline: RoughOutline,
        beat: Beat,
        reason: str,
        *,
        scene_index: int | None = None,
    ) -> Beat:
        data = self._llm.complete_json(
            Role.PLANNER,
            _REPLAN_SYSTEM,
            _REPLAN_USER.format(
                title=bible.title,
                chapter=beat.chapter,
                goal=beat.goal,
                reason=reason,
                cast="、".join(beat.cast),
                location=beat.location,
                conflict=outline.main_conflict,
            ),
            temperature=0.8,
        )
        return Beat(
            beat_id=f"{beat.beat_id}:replan",
            chapter=beat.chapter,
            # 换目标就是另一场戏，必须占一个新的故事时刻，否则两场戏的
            # State 写在同一个 valid_from 上，前一场的版本被压成零宽区间。
            scene_index=beat.scene_index if scene_index is None else scene_index,
            goal=data.get("goal", beat.goal),
            cast=beat.cast,
            location=beat.location,
            must_happen=tuple(data.get("must_happen", [])),
            done_when=data.get("done_when", beat.done_when),
        )
