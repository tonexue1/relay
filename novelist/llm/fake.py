"""离线假模型。

作用不是"能跑就行"，而是把两类问题拆开：
编排、闸门、记忆读写对不对（这里能验），文笔和判断力好不好（只有真模型能验）。

所以假模型刻意做到"守规矩"：位置取自必带 State，引用只取导演给的编号。
如果流水线在离线模式下都能被闸门拦住，那就是编排本身写错了。
"""

from __future__ import annotations

import re
from typing import Any

from .provider import Role, ScriptedProvider

_OWNER_RE = re.compile(r"你是(.+?)。")
_LOCATION_RE = re.compile(r"^- location：(.+)$", re.MULTILINE)
_CITABLE_RE = re.compile(r"\[(mem_[0-9a-f]+)\]")
_PARTNERS_RE = re.compile(r"在场的还有：(.+)")
_CHAPTER_RE = re.compile(r"第 (\d+) 章")
_ONSTAGE_RE = re.compile(r"在场：(.+)")
_CAST_RE = re.compile(r"这场戏在场角色：(.+)")
_BEAT_NO_RE = re.compile(r"这是第 (\d+) 拍")
_BEAT_COUNT_RE = re.compile(r"规划 (\d+) 个 beat")
#: 动作序列里的一行：`林晚：把针搁回绣绷，台词「……」`
_ACTOR_LINE_RE = re.compile(r"^(.+?)：(.+)$", re.MULTILINE)
_STATE_LINE_RE = re.compile(r"^- (.+?)：(.*)$", re.MULTILINE)
_ACTION_RE = re.compile(r"^\d+\. (.+?)：(.+)$", re.MULTILINE)
_CRITIC_OWNER_RE = re.compile(r"^角色：(.+)$", re.MULTILINE)
_SO_FAR_RE = re.compile(r"^本场已经发生", re.MULTILINE)
_USED_TITLE_RE = re.compile(r"^- (?!（无）)(.+)$", re.MULTILINE)
_REVISE_RE = re.compile(r"【待修订的正文】\n(.+)", re.DOTALL)


def _revised_prose(user: str) -> str:
    """假的定向修订：原文照抄再补一句，长度不缩，过得了防截断护栏。"""
    match = _REVISE_RE.search(user)
    original = match.group(1).strip() if match else ""
    return f"{original}（已按审校意见改过。）"


def _split_names(raw: str) -> list[str]:
    return [part.strip() for part in raw.replace("，", "、").split("、") if part.strip()]


def _chapter(user: str, default: int = 1) -> int:
    match = _CHAPTER_RE.search(user)
    return int(match.group(1)) if match else default


def _locations_by_character(user: str) -> dict[str, str]:
    """从 `character_states` 的输出里读出每个人此刻在哪。"""
    found: dict[str, str] = {}
    for name, body in _STATE_LINE_RE.findall(user):
        match = re.search(r"location=([^；\n]+)", body)
        if match:
            found[name.strip()] = match.group(1).strip()
    return found


def build_fake_provider(playable: tuple[str, ...], total_chapters: int) -> ScriptedProvider:
    def planner(system: str, user: str) -> dict[str, Any]:
        if '"acts"' in user:
            span = max(1, total_chapters // 3)
            return {
                "main_conflict": "林晚要一个答案，沈砚给不出来",
                "ending": "她弄清了原因，然后离开",
                "acts": [
                    {
                        "name": "第一幕 动身",
                        "summary": "林晚决定走",
                        "chapter_from": 1,
                        "chapter_to": span,
                    },
                    {
                        "name": "第二幕 在路上",
                        "summary": "路途消耗她",
                        "chapter_from": span + 1,
                        "chapter_to": max(span + 1, total_chapters - 1),
                    },
                    {
                        "name": "第三幕 见面",
                        "summary": "答案到手",
                        "chapter_from": max(span + 2, total_chapters),
                        "chapter_to": total_chapters,
                    },
                ],
            }

        if '"beats"' in user:
            chapter = _chapter(user)
            locations = _locations_by_character(user)
            # 把同一地点的人凑成一场，保证在场表和必带 State 对得上。
            grouped: dict[str, list[str]] = {}
            for name, place in locations.items():
                grouped.setdefault(place, []).append(name)
            if grouped:
                place, cast = max(grouped.items(), key=lambda kv: len(kv[1]))
            else:
                place, cast = "临安", list(playable[:2])
            # 要几个就给几个：不认这个数，配置里的 beats_per_chapter 在离线跑上
            # 就永远是 1，"一章多场只落一次笔"这类断言等于没测。
            count_match = _BEAT_COUNT_RE.search(user)
            count = int(count_match.group(1)) if count_match else 1
            return {
                "beats": [
                    {
                        "goal": f"第{chapter}章之{index}：在{place}把话说到不能再拖",
                        "cast": cast[:2],
                        "location": place,
                        "must_happen": [f"第{chapter}章里有人先松口"],
                        "done_when": "有人明确表态",
                    }
                    for index in range(1, count + 1)
                ]
            }

        return {
            "goal": "换个说法再逼一次",
            "must_happen": ["有人先动手"],
            "done_when": "有人明确表态",
        }

    def director(system: str, user: str) -> dict[str, Any]:
        if "REACHED|CONTINUE|STALLED" in user:
            return {"status": "REACHED", "reason": "已经有人表态"}

        match = _ONSTAGE_RE.search(user)
        cast = _split_names(match.group(1)) if match else list(playable[:2])

        # 拍级调度：按名单一人一拍，走完就收场。真模型会顺着刚发生的事挑人，
        # 假模型只保证"每个在场的人恰好演一拍、且能收场"，让编排可被确定性地验。
        if '"act 或 end"' in user:
            beat_match = _BEAT_NO_RE.search(user)
            beat_no = int(beat_match.group(1)) if beat_match else 1
            if beat_no > len(cast):
                return {"action": "end", "reason": "该说的都说了"}
            return {
                "action": "act",
                "actor": cast[beat_no - 1],
                "stage": "",
                "reason": f"轮到{cast[beat_no - 1]}",
            }

        return {
            "scene_goal": "把该说的话说出来",
            "order": cast,
            "briefs": {name: f"{name}想把自己的立场说清楚" for name in cast},
        }

    def actor(system: str, user: str) -> dict[str, Any]:
        owner_match = _OWNER_RE.search(user)
        owner = owner_match.group(1) if owner_match else playable[0]

        location_match = _LOCATION_RE.search(user)
        location = location_match.group(1).strip() if location_match else None

        partners_match = _PARTNERS_RE.search(user)
        partners = _split_names(partners_match.group(1)) if partners_match else []
        partners = [p for p in partners if p != "（无人）"]

        # 只引用导演实际给出的编号。
        citable = _CITABLE_RE.findall(user)

        # 收到"本场已经发生"就必须往下走，不能把同一件事再做一遍。
        if _SO_FAR_RE.search(user):
            intent = f"{owner}把椅子往后推开，站了起来"
            dialogue = "既然话说到这儿，我不再等了。"
        else:
            intent = f"{owner}把手里的东西放到桌上，没有抬头"
            dialogue = "这件事我今天要问清楚。"

        return {
            "intent": intent,
            "dialogue": dialogue,
            "emotion": "克制",
            "cites": citable[:1],
            "claimed_location": location,
            "position_detail": "桌边",
            "addresses": partners[:1],
            "mentions": [],
        }

    def critic(system: str, user: str) -> dict[str, Any]:
        owner_match = _CRITIC_OWNER_RE.search(user)
        owner = owner_match.group(1).strip() if owner_match else playable[0]
        return {
            "accepted": True,
            "feedback": "",
            "state_changes": [
                {
                    "owner_id": owner,
                    "field_id": "current_goal",
                    "value": "把这次问出来的话走到底",
                    "reason": "他刚刚把话说开了",
                }
            ],
        }

    def titler(system: str, user: str) -> str:
        used = _USED_TITLE_RE.findall(user)
        return f"对坐第{len(used) + 1}回"

    def narrator(system: str, user: str) -> str:
        # 叙述者身兼两职：落笔和定向修订。按 prompt 特征分流。
        if _REVISE_RE.search(user):
            return _revised_prose(user)
        chapter = _chapter(user)
        lines = []
        for name, intent in _ACTION_RE.findall(user):
            lines.append(f"{intent}。")
        body = "".join(lines) or "屋里很静。"
        return f"（第{chapter}章）{body}窗外的雨停了一阵，又落下来。"

    def reviewer(system: str, user: str) -> str:
        """守规矩的审校：先写审查过程，再吐空清单。

        默认判通过——离线跑的是编排，不是文笔。要验修订链路的测试自己覆盖它。
        """
        return (
            "锚点：时空、人物、事实三类均已对照。\n"
            "逐段核对：正文各处细节与动作序列一致，未见新增事件。\n"
            '{"issues":[]}'
        )

    def chronicler(system: str, user: str) -> dict[str, Any]:
        chapter = _chapter(user)
        match = _CAST_RE.search(user)
        cast = _split_names(match.group(1)) if match else list(playable[:2])
        # 抽取器读的是动作序列，只给真的动过的人长记忆——不然离线跑出来的
        # 记忆条数跟在场表挂钩，而不是跟实际演了几拍挂钩，测试就看不出漏拍。
        acted = [name for name, _ in _ACTOR_LINE_RE.findall(user)]
        cast = [name for name in cast if name in acted] or cast
        return {
            "world_episodes": [f"第{chapter}章：{'、'.join(cast)}当面把事情摊开了。"],
            "per_character": {
                name: {
                    "episodes": [
                        {
                            "summary": f"第{chapter}章，我在场，我把话说出口了。",
                            "tags": ["对峙"],
                            "salience": 0.7,
                        }
                    ],
                    "states": [
                        {"field_id": "status", "value": "疲惫"},
                    ],
                    "reflections": [
                        {
                            "memory_key": "self_model",
                            "summary": f"第{chapter}章之后，我不再指望别人先开口。",
                        }
                    ],
                }
                for name in cast
            },
        }

    return ScriptedProvider(
        {
            Role.PLANNER: planner,
            Role.DIRECTOR: director,
            Role.ACTOR: actor,
            Role.CRITIC: critic,
            Role.NARRATOR: narrator,
            Role.REVIEWER: reviewer,
            Role.TITLER: titler,
            Role.CHRONICLER: chronicler,
        }
    )
