"""审校：成文之后的一道网。

为什么需要它。L1 闸门和 L2 质检都在【动作提议】这一层，管的是演员想做什么。
可是正文是叙述者写的，它拿着动作序列重新落笔，这一步之后没有任何人再读一遍。
真模型上出现过叙述者凭空补出"出城半里、河边喝水、卖梨小贩"一整段——动作序列
里根本没有，闸门和质检都不可能发现，因为它们早在成文前就跑完了。

它【只挑刺、不改稿】：找硬伤是审校的强项，落笔是叙述者的强项。让审校兼职改写的
结果是改得太多、文风被改花。审校列清单，叙述者定向修订，再回来复审。

方法是先立锚点再逐句扫描，而不是针对每个见过的破绽打一条补丁。锚点全部来自账本，
不是让它凭语感判断。
"""

from __future__ import annotations

import json
import re

from llm.provider import LLMProvider, Role

from .context import SceneAnchors
from .models import (
    CritiqueResult,
    NarratableScene,
    ReviewIssue,
    render_scene_blocks,
)
from .rules import NO_INVENTION_STANDARD, SELF_CONSISTENCY_STANDARD

_SYSTEM = """你是一位严格的责任编辑，为一段已成文的小说做自洽审校。
你的职责只有一个：把硬伤找全、逐条列出来。你只挑刺，不改稿。

审校标准：{consistency}

另一条同等重要：{no_invention}
正文里出现了动作序列之外的新事件——多走一段路、多去一个地方、多认识一个人、
替人物完成了序列里没写完的事——都算硬伤，逐条列出。

【方法：先立锚点，再逐句扫描】
第一步·立锚点。从给你的设定和锚点块里立起三类基准：
  ① 时空锚点：此刻在哪、什么时辰、距前文已发生的事过去多久；
  ② 人物锚点：每个人的身份、性格、生理限制、说话风格、此刻所在地；
  ③ 事实锚点：已故者、关键物品归属、前文已确立的情节。
第二步·逐句扫描。把正文里每一处具体细节——痕迹、动作、天气、身体状态、物品、
称谓、因果——逐一拎出来追问：它与三类锚点冲突吗？合不合常识？动作序列里有吗？
  特别警惕两类：
  ① 错置的即时细节——只有"刚刚发生"才成立的痕迹，被安到了很久以前的事上；
  ② 悄悄新增的情节——正文顺着写下去，把序列没写的事替人物做完了。

只报确属硬伤的矛盾。文风、用词、润色一类主观好恶不归你管，不要报。

【输出格式：先审后报，两步走，都要短】
第一步·列审查清单，用紧凑的短行，不要写成大段议论：
  锚点：地点=… / 时间=… / 在场=… / 已故=… / 物品=…
  然后逐段一行，格式为「段N：可疑处 → 成立 或 硬伤+理由」。
  每行不超过四十字。这一步是为了逼你真去查——直接下结论"没问题"是被禁止的，
  但也不要复述正文、不要夸奖文笔、不要展开分析。行内不要出现花括号。
第二步·紧接着输出一个 JSON 对象汇总硬伤，这是最重要的一步，务必留足篇幅写完：
{{"issues":[{{"quote":"原句片段（逐字照抄）","why":"为何是硬伤（撞哪条锚点）","fix":"改法方向（只给方向）"}}]}}
逐段核对后确无硬伤，才输出 {{"issues":[]}}。
同一类硬伤散布多处也要逐处列全，别只列一处。"""

_USER = """【本章要达成】{goal}

{anchors}
【原始动作依据（谁说谁做以此为准，正文不得超出这个范围）】
本章分 {scene_count} 场，场与场之间换地点、换时间是正常的，不要报成硬伤；
但同一场之内的地点、在场人、动作归属必须与下面一致。

{scenes}

【待审校的正文】
{prose}

先列紧凑的审查清单（立锚点 + 逐段一行），随即输出硬伤清单 JSON。清单不能省。"""

_JSON_RE = re.compile(r"\{[^{}]*\"issues\"\s*:\s*\[.*?\]\s*\}", re.DOTALL)


class Reviewer:
    def __init__(self, llm: LLMProvider) -> None:
        self._llm = llm

    def critique(
        self,
        prose: str,
        scenes: tuple[NarratableScene, ...],
        anchors: SceneAnchors | None = None,
        goal: str = "",
    ) -> CritiqueResult:
        """审一整章。

        依据用的是叙述者拿到的那一份分场渲染，不是重新拼一份：审的正是"叙述者
        有没有超出它拿到的依据"，两边对不上，这个判断就无从谈起。分场给还能让它
        知道换地点是正常的——只给一个地点，它会把第二场整场报成硬伤。
        """
        if not prose.strip() or not scenes:
            return CritiqueResult()
        # 走纯文本而不是 JSON 模式：审查过程要先用自然语言写出来才逼得动模型真去查，
        # JSON 模式下它会直接跳到结论。
        raw = self._llm.complete_text(
            Role.REVIEWER,
            _SYSTEM.format(
                consistency=SELF_CONSISTENCY_STANDARD,
                no_invention=NO_INVENTION_STANDARD,
            ),
            _USER.format(
                goal=goal or "；".join(s.goal for s in scenes),
                anchors=(anchors.render() + "\n\n") if anchors else "",
                scene_count=sum(1 for s in scenes if s.actions),
                scenes=render_scene_blocks(scenes),
                prose=prose,
            ),
            temperature=0.2,
        )
        return parse_critique(raw)


def parse_critique(content: str) -> CritiqueResult:
    """从审校产出末尾抠出硬伤清单。

    解析不出时返回 parsed=False + 空清单，让上层按"审不出、暂当通过"兜底——
    既不崩，也不把解析失败误判成有硬伤。
    """
    matches = _JSON_RE.findall(content or "")
    if not matches:
        return CritiqueResult(parsed=False)
    try:
        data = json.loads(matches[-1])
    except json.JSONDecodeError:
        return CritiqueResult(parsed=False)
    raw_issues = data.get("issues")
    if not isinstance(raw_issues, list):
        return CritiqueResult(parsed=False)

    issues = []
    for raw in raw_issues:
        if not isinstance(raw, dict):
            continue
        quote = str(raw.get("quote", "")).strip()
        why = str(raw.get("why", "")).strip()
        # 至少要有原句或理由才算一条，避免空对象污染清单。
        if not quote and not why:
            continue
        issues.append(ReviewIssue(quote, why, str(raw.get("fix", "")).strip()))
    return CritiqueResult(tuple(issues), parsed=True)
