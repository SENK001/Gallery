#!/usr/bin/env python3
"""校验主题色板：每个颜色都必须在 values/ 与 values-night/ 里成对定义。

背景：项目约定「所有颜色都要有浅色与深色两份」。漏掉任何一份，深色模式下就会
回退到浅色值 —— 典型表现是「白块」，或者近黑字压在近黑底上导致文字不可见。
靠人工 review 一定会漏，所以用脚本守。

同时检查两件容易出错的事：
  1. values-night/colors.xml 里定义了浅色没有的名字（拼写错误）
  2. 代码/布局里引用了不存在的 @color/xxx（改名未收尾）

用法：
    python scripts/check-color-pairs.py          # 校验，失败返回非 0
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULES = ("main", "main-ui")

COLOR_DEF = re.compile(r'<color\s+name="([^"]+)"')
COLOR_REF = re.compile(r'@color/([A-Za-z0-9_]+)')

# 这些名字是「有意只定义一份」的说明白名单（当前为空，留作扩展）。
ALLOWED_LIGHT_ONLY: set[str] = set()


def defined_names(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return set(COLOR_DEF.findall(path.read_text(encoding="utf-8")))


def state_list_names(res: Path) -> set[str]:
    """res/color/ 下的 ColorStateList（文件名即资源名）。

    它们不是普通颜色、不需要 night 版本（内部引用的状态色各自成对即可），
    但同样会被 `@color/xxx` 引用，因此要计入「已定义」集合。
    """
    d = res / "color"
    return {f.stem for f in d.glob("*.xml")} if d.exists() else set()


def referenced_names() -> dict[str, set[str]]:
    """扫描 res 与源码，收集所有 @color/xxx 引用。"""
    refs: dict[str, set[str]] = {}
    for module in MODULES:
        base = REPO / module / "src" / "main"
        for sub in ("res", "java"):
            root = base / sub
            if not root.exists():
                continue
            for f in root.rglob("*"):
                if f.suffix not in (".xml", ".kt", ".java"):
                    continue
                for name in COLOR_REF.findall(f.read_text(encoding="utf-8")):
                    refs.setdefault(name, set()).add(str(f.relative_to(REPO)))
    return refs


def main() -> int:
    errors: list[str] = []
    all_defined: set[str] = set()

    for module in MODULES:
        res = REPO / module / "src" / "main" / "res"
        light = defined_names(res / "values" / "colors.xml")
        night = defined_names(res / "values-night" / "colors.xml")
        states = state_list_names(res)
        all_defined |= light | night | states

        missing_night = light - night - ALLOWED_LIGHT_ONLY
        extra_night = night - light

        print(f"[{module}] values={len(light)}  values-night={len(night)}  color/state-list={len(states)}")
        if missing_night:
            errors.append(
                f"{module}: 缺 values-night 定义（深色下会回退成浅色值）: "
                + ", ".join(sorted(missing_night))
            )
        if extra_night:
            errors.append(
                f"{module}: values-night 有浅色未定义的名字（可能是拼写错误）: "
                + ", ".join(sorted(extra_night))
            )

    # 引用了但不存在（改名未收尾的典型症状）
    unresolved = {n: files for n, files in referenced_names().items() if n not in all_defined}
    if unresolved:
        for name, files in sorted(unresolved.items()):
            errors.append(f"引用了不存在的 @color/{name}（{len(files)} 处）: " + ", ".join(sorted(files)[:3]))

    print()
    if errors:
        print("FAIL —— 主题色板校验未通过：")
        for e in errors:
            print("  - " + e)
        return 1

    print(f"OK —— {len(all_defined)} 个颜色全部成对定义，且无悬空引用。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
