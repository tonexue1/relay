"""从 YAML 读 Bible。

Bible 是作者的设定，是宿主的真相源。校验放在读取时做，
错的设定不该等到第 3 章才炸。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from .models import Bible, CharacterSpec, FieldSeed, WORLD_OWNER
from .style import INTENSITIES, load_style_card

_VALID_KINDS = {"TEXT", "ENUM", "ENUM_LIST", "TEXT_LIST", "NUMBER"}


def load_bible(path: str | Path) -> Bible:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))

    fields = tuple(
        FieldSeed(
            field_id=raw["field_id"],
            kind=raw.get("kind", "TEXT"),
            allowed_values=tuple(raw.get("allowed_values", [])),
            user_lock=bool(raw.get("user_lock", False)),
            max_items=int(raw.get("max_items", 32)),
            extractor_locked=bool(raw.get("extractor_locked", False)),
        )
        for raw in data.get("state_fields", [])
    )
    for seed in fields:
        if seed.kind not in _VALID_KINDS:
            raise ValueError(f"字段 {seed.field_id} 的 kind={seed.kind} 不支持")
        if seed.kind.startswith("ENUM") and not seed.allowed_values:
            raise ValueError(f"字段 {seed.field_id} 是 {seed.kind} 但没给 allowed_values")

    known_fields = {seed.field_id: seed for seed in fields}
    characters = []
    for raw in data.get("characters", []):
        initial = raw.get("initial_state") or {}
        for field_id, value in initial.items():
            seed = known_fields.get(field_id)
            if seed is None:
                raise ValueError(
                    f"{raw['owner_id']} 的初始状态用了未声明字段 {field_id}"
                )
            if seed.kind == "ENUM" and value not in seed.allowed_values:
                raise ValueError(
                    f"{raw['owner_id']}.{field_id}={value!r} 不在闭集 {seed.allowed_values}"
                )
        characters.append(
            CharacterSpec(
                owner_id=raw["owner_id"],
                persona=raw.get("persona", ""),
                voice=raw.get("voice", ""),
                secrets=tuple(raw.get("secrets", [])),
                initial_state=initial,
            )
        )

    owner_ids = [c.owner_id for c in characters]
    if len(owner_ids) != len(set(owner_ids)):
        raise ValueError("characters 里有重复的 owner_id")
    if WORLD_OWNER not in owner_ids:
        raise ValueError(f"必须声明世界仓角色 {WORLD_OWNER!r}，客观事实存在它名下")
    if len(owner_ids) < 2:
        raise ValueError("至少要有一个可上场的角色")

    death_states = tuple(data.get("death_states", []))
    status_seed = known_fields.get("status")
    if death_states and status_seed and status_seed.allowed_values:
        stray = set(death_states) - set(status_seed.allowed_values)
        if stray:
            raise ValueError(
                f"death_states {sorted(stray)} 不在 status 闭集里；名册永远不会命中"
            )

    intensity = data.get("style_intensity", "medium")
    if intensity not in INTENSITIES:
        raise ValueError(f"style_intensity={intensity!r} 不支持；可选 {list(INTENSITIES)}")

    bible = Bible(
        space_id=data["space_id"],
        title=data["title"],
        premise=data["premise"],
        ending_direction=data["ending_direction"],
        total_chapters=int(data.get("total_chapters", 3)),
        world_rules=tuple(data.get("world_rules", [])),
        world_terms=tuple(data.get("world_terms", [])),
        taboos=tuple(data.get("taboos", [])),
        characters=tuple(characters),
        state_fields=fields,
        style=data.get("style", ""),
        style_card=data.get("style_card", ""),
        style_intensity=intensity,
        death_states=death_states,
    )

    if bible.style_card:
        # 提前加载一次：风味卡拼错该在读设定时就炸，而不是等第一次落笔。
        load_style_card(bible.style_card)

    required = {"location", "current_goal", "status"}
    missing = required - set(known_fields)
    if missing:
        raise ValueError(f"必带字段缺声明：{sorted(missing)}；导演每场都要读它们")

    for name in bible.playable_names:
        spec = bible.character(name)
        if "location" not in spec.initial_state:
            raise ValueError(f"{name} 缺 location 初始值；缺了第一场就会 Blocked")

    return bible


def dump_bible_summary(bible: Bible) -> str:
    lines = [
        f"《{bible.title}》 {bible.total_chapters} 章",
        f"前提：{bible.premise}",
        f"结局方向：{bible.ending_direction}",
        f"角色：{'、'.join(bible.playable_names)}",
        f"状态字段：{'、'.join(seed.field_id for seed in bible.state_fields)}",
    ]
    if bible.style_card:
        lines.append(f"风味卡：{bible.style_card}（{bible.style_intensity}）")
    return "\n".join(lines)
