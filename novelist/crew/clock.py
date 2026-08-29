"""故事时钟。

业务时间必须比"章"更细。一章里有多场戏，每场都可能改位置和目标；
一场戏内部还要分"场前读取"和"场后落账"两个时刻，否则本场写的状态会
被本场自己读到，等于让演员提前知道自己接下来要做什么。

所以刻度分三层：章占百位，场占十位，场内偏移占个位。

    第 0 章（Bible 初始状态）   = 0
    第 1 章第 0 场   场前读取   = 100
                     场中变更   = 101   （critic 判定过的状态）
                     场后落账   = 102   （Reflection）
    第 1 章第 1 场   场前读取   = 110
    第 1 章章末                 = 199
    第 2 章第 0 场   场前读取   = 200
"""

from __future__ import annotations

from ledger.types import ClockDomain, ClockStamp

CHAPTER_STRIDE = 100
SCENE_STRIDE = 10
MAX_SCENES_PER_CHAPTER = CHAPTER_STRIDE // SCENE_STRIDE

#: 场内偏移。留在 SCENE_STRIDE 之内，保证不越到下一场。
OFFSET_SCENE_OPEN = 0
OFFSET_IN_SCENE = 1
OFFSET_AFTER_SCENE = 2


def story_time(chapter: int, scene: int = 0, offset: int = OFFSET_SCENE_OPEN) -> int:
    if not 0 <= scene < MAX_SCENES_PER_CHAPTER:
        raise ValueError(f"场次 {scene} 超出单章容量 {MAX_SCENES_PER_CHAPTER}")
    if not 0 <= offset < SCENE_STRIDE:
        raise ValueError(f"场内偏移 {offset} 超出 {SCENE_STRIDE}")
    return chapter * CHAPTER_STRIDE + scene * SCENE_STRIDE + offset


def chapter_end(chapter: int) -> int:
    """章末：晚于本章任何一场，早于下一章第 0 场。"""
    return chapter * CHAPTER_STRIDE + CHAPTER_STRIDE - 1


def stamp(chapter: int, scene: int = 0, offset: int = OFFSET_SCENE_OPEN) -> ClockStamp:
    return ClockStamp(ClockDomain.STORY_TIME, story_time(chapter, scene, offset))


def end_stamp(chapter: int) -> ClockStamp:
    return ClockStamp(ClockDomain.STORY_TIME, chapter_end(chapter))


def at(value: int) -> ClockStamp:
    return ClockStamp(ClockDomain.STORY_TIME, value)
