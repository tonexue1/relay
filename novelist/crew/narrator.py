"""叙述者：唯一决定文风的人。

输入是已经过闸的动作序列，所以叙述者不需要判断真假，只负责把动作写成散文。
一章一次调用：章内连贯由这一次调用的全局视角保证。一场一次的话，一章里会有
好几段各自独立生成的正文，接缝是真实存在的——真模型上撞过，同一章把"踩石阶"
写了两遍，因为后一场看不见前一场已经写过了。

它还兼任【定向修订】：审校挑出硬伤，由它落笔改。找硬伤是审校的强项，写文字是
叙述者的强项，让审校兼职改写的结果是改得太多、文风被改花。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

from .context import SceneAnchors
from .models import Bible, NarratableScene, ReviewIssue, render_scene_blocks
from .rules import NO_INVENTION_STANDARD, SELF_CONSISTENCY_STANDARD

_SYSTEM = """你是这本小说的叙述者。你把已经定好的动作序列写成正文。

铁律：
1. 【不许无中生有】{no_invention}
2. 【细节自洽】{consistency}
3. 严格保持每条动作和台词的归属：谁说的、谁做的，必须与动作序列里的行动者一致，
   绝不张冠李戴、合并或对调说话人。
4. 按给定顺序推进，不要重排。
5. 一章分成几场，场与场之间可以换地点换时间，但要一气贯下来：后一场要接得住
   前一场的结果，不要把前面已经写过的动作再写一遍。整章只有一个笔调。
6. 不写章节标题，不写总结句，不解释人物心理动机——用动作和台词呈现。
7. 对白一律用直角引号「」，全章统一，不要前半段「」后半段“”。
8. 只输出正文。场与场之间用一个空行分隔，不要写"第一场""场景二"这类标记。"""

_USER = """《{title}》第 {chapter} 章。

{style}
{anchors}本章分 {scene_count} 场，动作序列按顺序给出（不要增删、不要重排）：

{scenes}

写成整章正文，{length} 字左右（宁短不加戏）。"""


_REVISE_SYSTEM = """你是这一章的叙述者，现在做一次【定向修订】。
审校挑出了若干硬伤，你要逐条改掉，别的一律不动。

判断硬伤的标准：{consistency}

补丁纪律——这次你是打补丁的人，不是重写的人：
1. 硬伤必须改掉：清单每一条都要落实到正文里。漏改一条，比多改十个字还糟。
   同一类硬伤散落多处（比如一个称呼错了一整段），要逐处都改到。
2. 只动被点名处：除清单点名的地方外一字不改——不润色、不加新情节新对白、
   不删原有句段、不改文风。
3. 改动幅度就低不就高：能改一个字（"他"→"她"、"绣坊"→"城门"）就绝不重写整句，
   只在硬伤所在那句的最小范围内落笔。
4. 保次序：绝不重排段落或句子的先后，不合并、不拆分段落。原稿怎么排，改完还怎么排。
5. 替换而非删除：该换成对等说法的，不要把整句删空。
6. 锚点只用来判对错，不许抄进正文：锚点里提到、而正文原本没写的东西，一律不得写入。

交稿前双向自检：a) 清单每条是否真的改掉了、改后与锚点和常识自洽？
b) 有没有手滑动到没被点名的地方，尤其是段落顺序、有没有多写了原文没有的东西？
两头都干净才算过关。

输出：只输出修订后的完整正文，从头到尾一字不落、段落顺序与原稿完全一致。
不要解释、不要保留批注、不要标注改了哪里。"""

_REVISE_USER = """{anchors}
【审校挑出的硬伤（逐条改掉，别的不碰）】
{issues}

【待修订的正文】
{prose}

按补丁纪律做定点修订，只输出修订后的完整正文。"""


def render_issues(issues: tuple[ReviewIssue, ...]) -> str:
    blocks = []
    for index, issue in enumerate(issues, start=1):
        lines = [f"{index}. 原句：{issue.quote or '（未给出原句，按"问题"定位）'}"]
        if issue.why:
            lines.append(f"   问题：{issue.why}")
        if issue.fix:
            lines.append(f"   改法方向：{issue.fix}")
        blocks.append("\n".join(lines))
    return "\n".join(blocks)


class Narrator:
    def __init__(self, llm: LLMProvider, bible: Bible, style_brief: str = "") -> None:
        self._llm = llm
        self._bible = bible
        self._style = style_brief or f"文风要求：{bible.style or '白描，节制，少形容词'}"

    def narrate_chapter(
        self,
        chapter: int,
        scenes: tuple[NarratableScene, ...],
        *,
        length: int = 1_200,
        anchors: SceneAnchors | None = None,
    ) -> str:
        """一章的全部动作序列，一次落笔。"""
        blocks = render_scene_blocks(scenes)
        if not blocks:
            return ""

        anchor_block = anchors.render() if anchors else ""
        return self._llm.complete_text(
            Role.NARRATOR,
            _SYSTEM.format(
                no_invention=NO_INVENTION_STANDARD,
                consistency=SELF_CONSISTENCY_STANDARD,
            ),
            _USER.format(
                title=self._bible.title,
                chapter=chapter,
                style=self._style,
                anchors=f"{anchor_block}\n\n" if anchor_block else "",
                scene_count=sum(1 for s in scenes if s.actions),
                scenes=blocks,
                length=length,
            ),
            temperature=0.9,
        )

    def revise(
        self,
        prose: str,
        issues: tuple[ReviewIssue, ...],
        anchors: SceneAnchors | None = None,
    ) -> str:
        """按硬伤清单定点修订，输出整段正文。

        输出整段而不是查找替换补丁：让模型只吐补丁时它抄不准原句，find 对不上，
        硬伤就修不掉。重出整段能稳稳修好，副作用靠上面的补丁纪律压住。

        刻意不喂动作序列：喂了会诱使叙述者把正文往序列次序上"对齐"而重排，越改越多。
        """
        if not issues:
            return prose
        anchor_block = anchors.render(for_revision=True) if anchors else ""
        return self._llm.complete_text(
            Role.NARRATOR,
            _REVISE_SYSTEM.format(consistency=SELF_CONSISTENCY_STANDARD),
            _REVISE_USER.format(
                anchors=f"{anchor_block}\n\n" if anchor_block else "",
                issues=render_issues(issues),
                prose=prose,
            ),
            temperature=0.3,
        )
