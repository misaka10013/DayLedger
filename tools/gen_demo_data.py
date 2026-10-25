#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成一份「合成演示数据」（legacy_timelog.json）
====================================================================

## 这个脚本是干什么的

App 首次启动时会把 `app/src/main/assets/legacy_timelog.json` 里的旧版记录
导入数据库，这样一装上去就能看到内容，而不是一个空壳。

**但真实的出厂文件是作者自己的两年半生活记录，不能开源。**
所以这里造一份「形状完全一样、内容纯属虚构」的替身：

  · 同样是 60 天、每天 1440 分钟密铺（不重叠、不留缝）
  · 同样是那 8 个内置标签
  · 只用内置的 58 个事项名，不新建任何事项 → `newItems` 永远为空
  · 固定随机种子 → 每次跑出来的结果完全一致，方便 diff

## 怎么跑

    python3 tools/gen_demo_data.py

输出会覆盖 `app/src/main/assets/legacy_timelog.json`。

## 想改成自己的作息？

所有「时间点」都在下面的 `workday()` / weekend()` 两个函数里，一眼就能看到。
改完重跑即可，脚本末尾有自检（密铺 / 无重叠 / 事项名合法），不合法会直接报错。
"""

import json
import random
from datetime import date, timedelta

# ---------------------------------------------------------------- 基础设定

# ★ 只允许出现 App 内置的 58 个事项（见 SeedData.kt），否则导入时会报「缺少事项」
SEED_ITEMS = {
    "技能", "整理思绪", "英语", "印尼语", "日语", "编程", "写作",
    "AI讨论", "看书", "笔记", "健身", "唱歌", "爱好",
    "视频学习", "摄影", "骑行", "做饭", "音乐学习", "手工",
    "陪伴伴侣", "视频通话", "一起看剧", "语音通话", "一起吃饭", "计划未来",
    "线下面基", "在线闲聊", "无意义陪跑", "家人联系", "朋友聚会", "逛街",
    "打工", "兼职", "副业", "开车通勤", "加班", "赚钱",
    "生存必需品", "洗脸洗漱", "清洁家务", "洗澡", "采购", "取快递",
    "补番", "游戏", "刷资讯", "听音乐", "看小说", "玩手机",
    "睡觉", "午休", "吃饭", "发呆", "躺着",
    "待办杂事", "办事跑腿", "去医院", "去银行",
}

# ★★ 这 5 个**不在内置种子里** —— 它们走 `newItems` 那条路，由导入器现场建出来。
#
# 为什么要特意留这么几个？
# 因为「旧数据里有、新 App 没有的事项怎么办」是这个导入器最容易被写错的地方，
# 而它只有在真的遇到陌生事项时才会被走到。留 5 个，测试才能真正覆盖那条分支。
# （真实世界里的例子是作者自己的「洗护」「社交」这类自定义事项。）
NEW_ITEMS = [
    ("散步", "relax"),    # 放松娱乐
    ("浇花", "chore"),    # 杂事
    ("喂猫", "life"),     # 生活必需
    ("理发", "life"),     # 生活必需
    ("串门", "social"),   # 人际交往
]
NEW_ITEM_NAMES = {n for n, _ in NEW_ITEMS}

# ★ 只允许出现 App 内置的 8 个标签（见 SeedData.kt）
SEED_TAGS = {
    "高价值投资", "情感充电", "低价值消耗",
    "能量增益", "能量消耗", "日常必需",
    "健康维护", "社交经营",
}

# 每个事项默认挂哪些标签。没写的事项 = 不挂标签（App 允许）。
TAGS_OF = {
    "睡觉":     ["日常必需"],
    "午休":     ["日常必需"],
    "吃饭":     ["日常必需"],
    "洗脸洗漱": ["日常必需"],
    "洗澡":     ["日常必需"],
    "生存必需品": ["日常必需"],
    "发呆":     ["日常必需"],
    "躺着":     ["日常必需"],

    "打工":     ["日常必需", "能量消耗"],
    "加班":     ["日常必需", "能量消耗"],
    "开车通勤": ["日常必需", "能量消耗"],
    "兼职":     ["日常必需", "能量消耗"],

    "看书":     ["高价值投资", "能量增益"],
    "编程":     ["高价值投资", "能量增益"],
    "英语":     ["高价值投资", "能量增益"],
    "技能":     ["高价值投资", "能量增益"],
    "写作":     ["高价值投资", "能量增益"],
    "视频学习": ["高价值投资", "能量增益"],
    "AI讨论":   ["高价值投资", "能量增益"],
    "笔记":     ["高价值投资", "能量增益"],

    "玩手机":   ["低价值消耗"],
    "刷资讯":   ["低价值消耗"],
    "游戏":     ["情感充电"],
    "补番":     ["情感充电"],
    "看小说":   ["情感充电"],
    "听音乐":   ["情感充电"],

    "健身":     ["健康维护", "能量增益"],
    "骑行":     ["健康维护", "能量增益"],
    "做饭":     ["日常必需", "能量增益"],

    "朋友聚会": ["社交经营", "情感充电"],
    "线下面基": ["社交经营", "情感充电"],
    "家人联系": ["社交经营", "能量增益"],
    "在线闲聊": ["社交经营"],

    "清洁家务": ["日常必需"],
    "采购":     ["日常必需"],
    "取快递":   ["日常必需"],
    "办事跑腿": ["日常必需"],

    # 下面这 5 个是 newItems（App 种子里没有），标签照样按内容挂
    "散步":     ["健康维护", "能量增益"],
    "浇花":     ["日常必需"],
    "喂猫":     ["日常必需"],
    "理发":     ["日常必需"],
    "串门":     ["社交经营", "情感充电"],
}

MINUTES_PER_DAY = 1440

# 演示数据覆盖的 60 天：2026-08-16（周日）→ 2026-10-14（周三）
START_DAY = date(2026, 8, 16)
DAYS = 60


# ---------------------------------------------------------------- 造一天

def workday(rng):
    """普通工作日：起床 → 通勤 → 上班 → 午休 → 上班 → 通勤 → 做饭 → 自由活动 → 睡。"""
    segs = []

    def add(a, b, item, note=""):
        """追加一段。★ 自动补缝 + 防重叠。

        手算时间点很容易漏几分钟，而「一天恒 1440 分钟、缝隙必须显式存在」
        是这个 App 的核心规则。所以这里统一兜一层：前一段没接到 `a` 就拿
        `发呆` 填上，`a` 落在前一段里面就推后到前一段终点。
        """
        if segs and a > segs[-1]["b"]:
            segs.append({"a": segs[-1]["b"], "b": a, "i": "发呆"})
        if segs and a < segs[-1]["b"]:
            a = segs[-1]["b"]
        if b <= a:
            return
        segs.append({"a": a, "b": b, "i": item, **({"n": note} if note else {})})

    wake = rng.randint(430, 465)                      # 07:10 ~ 07:45
    add(0, wake, "睡觉")
    add(wake, wake + rng.randint(15, 22), "洗脸洗漱")
    add(segs[-1]["b"], segs[-1]["b"] + rng.randint(18, 30), "吃饭", "早饭")

    leave = segs[-1]["b"] + rng.randint(0, 12)
    add(segs[-1]["b"], leave, "发呆")                 # 出门前的磨蹭
    arrive = leave + rng.randint(30, 42)
    add(leave, arrive, "开车通勤")

    # 上午上班，中间可能被打断一次（开会 / 跑腿）
    noon = 715 + rng.randint(-10, 10)
    lunch = arrive + rng.randint(150, 200)
    add(arrive, lunch, "打工")
    lunch_end = lunch + rng.randint(35, 55)
    add(lunch, lunch_end, "吃饭", "午饭")
    nap = lunch_end + rng.randint(15, 30)
    add(lunch_end, nap, "午休")

    # 下午上班
    off = 1080 + rng.randint(-8, 35)                  # 18:00 前后下班
    add(nap, off, "打工")

    # 加班日：一周大约一天
    if rng.random() < 0.2:
        add(off, off + rng.randint(45, 110), "加班")
        off = segs[-1]["b"]

    back = off + rng.randint(30, 45)
    add(off, back, "开车通勤")

    # 晚饭
    cook = back + rng.randint(0, 15)
    if rng.random() < 0.6:
        cook_end = cook + rng.randint(25, 50)
        add(cook, cook_end, "做饭")
        eat_end = cook_end + rng.randint(20, 35)
        add(cook_end, eat_end, "吃饭", "晚饭")
    else:
        eat_end = cook + rng.randint(0, 10)
        if eat_end > cook:
            add(cook, eat_end, "发呆")
        eat_end = segs[-1]["b"]
        add(eat_end, eat_end + rng.randint(25, 40), "吃饭", "晚饭")
        eat_end = segs[-1]["b"]

    # 晚上自由活动：先刷一会儿手机，再做点正事或者看剧
    #
    # ⚠️ 这里每个时间点都要「封顶」。加班日 + 晚下班可能把晚上推得很晚，
    #    如果一路加下去就会超过 1440 —— 那就不是「晚睡」，而是数据算错了。
    #    封顶之后最晚 22:30 收摊，至少留 90 分钟睡觉。
    slop = eat_end + rng.randint(20, 55)
    add(eat_end, slop, rng.choice(["玩手机", "刷资讯"]))
    hobby = rng.choices(
        ["看书", "编程", "英语", "视频学习", "补番", "游戏", "看小说", "散步"],
        weights=[3, 3, 1, 1, 2, 1, 1, 2],
    )[0]
    hobby_end = min(slop + rng.randint(40, 110), 1290)     # 21:30 收摊
    add(slop, hobby_end, hobby)

    shower_end = min(hobby_end + rng.randint(20, 35), 1320)  # 22:00
    add(hobby_end, shower_end, "洗澡")

    bed = min(shower_end + rng.randint(0, 25), 1350)         # 22:30
    add(shower_end, bed, "玩手机")
    add(bed, MINUTES_PER_DAY, "睡觉")

    return segs


def weekend(rng):
    """周末：起得晚，一天更松散，可能出门或者收拾屋子。"""
    segs = []

    def add(a, b, item, note=""):
        """追加一段。★ 自动补缝 + 防重叠（同 workday 里的说明）。"""
        if segs and a > segs[-1]["b"]:
            segs.append({"a": segs[-1]["b"], "b": a, "i": "发呆"})
        if segs and a < segs[-1]["b"]:
            a = segs[-1]["b"]
        if b <= a:
            return
        segs.append({"a": a, "b": b, "i": item, **({"n": note} if note else {})})

    wake = rng.randint(500, 600)                      # 08:20 ~ 10:00
    add(0, wake, "睡觉")
    phone = wake + rng.randint(20, 45)
    add(wake, phone, "玩手机")
    add(phone, phone + rng.randint(15, 25), "洗脸洗漱")

    # 早饭，或者干脆早午并一顿
    br = segs[-1]["b"] + rng.randint(0, 15)
    if br > segs[-1]["b"]:
        add(segs[-1]["b"], br, "发呆")
    meal_end = br + rng.randint(30, 50)
    add(br, meal_end, "吃饭", "早午饭")

    # 白天的活动：出门 / 在家收拾 / 学点东西
    kind = rng.choices(["out", "home", "study"], weights=[3, 3, 3])[0]
    cur = meal_end
    if kind == "out":
        travel = cur + rng.randint(20, 40)
        add(cur, travel, "开车通勤")
        # 出门干什么：采购 / 见朋友 / 串个门
        pick = rng.choices(["采购", "朋友聚会", "串门"], weights=[4, 4, 2])[0]
        stay = travel + (rng.randint(50, 120) if pick == "采购" else rng.randint(90, 160))
        add(travel, stay, pick)
        cur = stay
        back = cur + rng.randint(20, 40)
        add(cur, back, "开车通勤")
        cur = back
    elif kind == "home":
        chore1 = cur + rng.randint(40, 90)
        add(cur, chore1, "清洁家务")
        chore2 = chore1 + rng.randint(15, 40)
        add(chore1, chore2, rng.choice(["整理思绪", "浇花", "喂猫"]))
        cur = chore2
        # 在家顺手理个发
        if rng.random() < 0.2:
            hair = cur + rng.randint(25, 40)
            add(cur, hair, "理发")
            cur = hair
    else:
        study = cur + rng.randint(70, 140)
        add(cur, study, rng.choice(["看书", "编程", "英语", "视频学习"]))
        cur = study

    # 下午茶 / 运动
    if rng.random() < 0.45:
        sport = cur + rng.randint(40, 70)
        add(cur, sport, rng.choice(["骑行", "健身"]))
        cur = sport

    # 晚饭
    cook = cur + rng.randint(10, 40)
    add(cur, cook, "玩手机")
    cook_end = cook + rng.randint(35, 60)
    add(cook, cook_end, "做饭")
    eat_end = cook_end + rng.randint(25, 45)
    add(cook_end, eat_end, "吃饭", "晚饭")

    # 晚上：看剧 / 游戏 / 读书（同样封顶，出门日可能已经很晚了）
    fun_end = min(eat_end + rng.randint(70, 150), 1290)
    add(eat_end, fun_end, rng.choices(["补番", "游戏", "看小说", "看书"], weights=[3, 2, 2, 2])[0])

    shower_end = min(fun_end + rng.randint(20, 35), 1320)
    add(fun_end, shower_end, "洗澡")
    bed = min(shower_end + rng.randint(0, 40), 1360)
    add(shower_end, bed, "玩手机")
    add(bed, MINUTES_PER_DAY, "睡觉")

    return segs


# ---------------------------------------------------------------- 主流程

def build():
    rng = random.Random(20260816)          # 固定种子 → 每次跑结果一致

    raw_days = []
    for i in range(DAYS):
        d = START_DAY + timedelta(days=i)
        raw_days.append((d, weekend(rng) if d.weekday() >= 5 else workday(rng)))

    # ★ 保底：确保 5 个 newItems 都真的出现在记录里。
    #
    #   为什么这一条必须有？因为导入器只给「记录里出现了、但 App 库里没有」的事项
    #   建新条目。名字只写在 newItems 数组里、`days` 里一次都不用，
    #   那条「现场新建事项」的分支根本走不到 —— 测试也就白写了。
    #   做法是找一段「发呆 / 玩手机」这种纯填充段，直接顶替掉。
    used = {s["i"] for _, segs in raw_days for s in segs}
    spare = ("发呆", "玩手机")

    def find_slot(segs):
        return next((s for s in segs if s["i"] in spare and s["b"] - s["a"] >= 20), None)

    for k, (name, _cat) in enumerate(NEW_ITEMS):
        if name in used:
            continue
        hit = find_slot(raw_days[(k + 1) * 5 % len(raw_days)][1])
        if hit is None:                       # 那天没找到合适的，换一天
            for _d, segs in raw_days:
                hit = find_slot(segs)
                if hit is not None:
                    break
        assert hit is not None, f"找不到可以安放「{name}」的填充段"
        hit["i"] = name
        used.add(name)

    # ★ 跨天链：昨天 23:xx→24:00 和今天 00:00→07:xx 如果是**同一件事**，
    #   说明它是一件连着跨过了午夜的事，要给两段打同一个 `c`（chain id）。
    #   这个字段在旧版导出里就有，导入器 `LegacyImport` 读的就是它（`optString("c")`）。
    #   常见的例子就是睡觉 —— 所以这里按「末段 1440 / 首段 0 / 事项相同」自动连。
    chain_no = 0
    for k in range(1, len(raw_days)):
        prev = raw_days[k - 1][1][-1]
        cur = raw_days[k][1][0]
        if prev["b"] == MINUTES_PER_DAY and cur["a"] == 0 and prev["i"] == cur["i"]:
            chain_no += 1
            cid = "chain%04d" % chain_no
            prev["c"] = cid
            cur["c"] = cid

    days = []
    seg_count = 0
    recorded = 0

    for d, segs in raw_days:
        # 自检 1：密铺、从 0 开始、到 1440 结束、事项名合法
        assert segs[0]["a"] == 0, f"{d} 第一条不从 0 开始"
        assert segs[-1]["b"] == MINUTES_PER_DAY, f"{d} 最后一条不到 1440"
        for s in segs:
            assert 0 <= s["a"] < s["b"] <= MINUTES_PER_DAY, f"{d} 段越界 {s}"
            assert s["i"] in SEED_ITEMS or s["i"] in NEW_ITEM_NAMES, \
                f"{d} 出现了既不在内置、也不在 newItems 里的事项：{s['i']}"
        for x, y in zip(segs, segs[1:]):
            assert x["b"] == y["a"], f"{d} 在 {x['b']} 处断开了（{x['i']} → {y['i']}）"

        # 补齐标签
        for s in segs:
            tags = [t for t in TAGS_OF.get(s["i"], []) if t in SEED_TAGS]
            if tags:
                s["t"] = tags

        # 统一字段顺序，让输出好看一点（a/b/i 必填，n/t/c 有才写）
        ordered = []
        for s in segs:
            o = {"a": s["a"], "b": s["b"], "i": s["i"]}
            if s.get("n"):
                o["n"] = s["n"]
            if s.get("t"):
                o["t"] = s["t"]
            if s.get("c"):
                o["c"] = s["c"]
            ordered.append(o)

        days.append({"d": d.isoformat(), "s": ordered})
        seg_count += len(ordered)
        recorded += MINUTES_PER_DAY

    return {
        "version": 1,
        "generatedAt": "2026-10-14T23:59:00",
        "source": "demo（合成演示数据，非真实记录；由 tools/gen_demo_data.py 生成）",
        "stats": {
            "sourceRows": seg_count,
            "usedRows": seg_count,
            "droppedZeroDuration": 0,
            "droppedCollapsed": 0,
            "trimmedByOverlap": 0,
            "crossDayRows": chain_no,
            "days": len(days),
            "segments": seg_count,
            "recordedMinutes": recorded,
            "expectedGapMinutes": 0,
            "firstDay": days[0]["d"],
            "lastDay": days[-1]["d"],
        },
        # ★ 这 5 个是「App 里没有、需要现场建出来」的事项（走导入器的 newItems 分支）
        "newItems": [{"name": n, "categoryId": c} for n, c in NEW_ITEMS],
        "items": sorted({s["i"] for d in days for s in d["s"]}),
        "tags": sorted({t for d in days for s in d["s"] for t in s.get("t", [])}),
        "days": days,
    }


if __name__ == "__main__":
    import os
    import sys

    data = build()
    out = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "legacy_timelog.json",
    )
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))

    st = data["stats"]
    print(f"已写入 {out}")
    print(f"  {st['days']} 天 / {st['segments']} 段 / {st['recordedMinutes']} 分钟")
    print(f"  事项 {len(data['items'])} 种（其中 {len(NEW_ITEMS)} 个是现场新建的），标签 {len(data['tags'])} 种")
    print(f"  体积 {os.path.getsize(out) / 1024:.0f} KB")
