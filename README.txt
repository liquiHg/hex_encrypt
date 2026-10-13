# Hex Encryption · 中英双语版 / Bilingual Edition

---

# Hex Encryption（中文）

**Hex Casting × Iron's Spells 'n Spellbooks 的桥接模组**

把 Iron's Spells 'n Spellbooks（以下简称 ISS）的每一个法术，变成一个可以在 Hex Casting 里绘制、施放、且拥有独特图案的 Hex 动作。玩家可以在「法术解析台」上绘制图案、入栈、解析，最终把 ISS 法术「翻译」成 Hex 图案，并通过 Hex 施法解锁 ISS 法术本身。

---

## 目录

1. [概述](#概述)
2. [依赖](#依赖)
3. [ISS 法术 Hex 化](#iss-法术-hex-化)
4. [法术解析台](#法术解析台)
5. [自定义操作符](#自定义操作符)
6. [音色系统](#音色系统)
7. [施法解锁](#施法解锁)
8. [命令](#命令)
9. [配置](#配置)
10. [常见问题](#常见问题)
11. [许可](#许可)

---

## 概述

模组主要做四件事：

- **为每个 ISS 法术自动注册一个 Hex 动作**，图案由「官能团前缀 + hash 后缀」生成，保证唯一且可读。
- **提供「法术解析台」方块**，让玩家用绘制的方式把 ISS 法术图案「破解」出来。
- **补充三个自定义 Hex 操作符**：设置目标、清除目标、设置音色。
- **通过 Hex 施法解锁 ISS 法术**，在玩家客户端缓存解锁状态，并给予解锁反馈。

---

## 依赖

| 依赖 | 版本要求 |
|---|---|
| Minecraft | 1.20.1（`[1.20.1, 1.21)`） |
| Forge | 47+ |
| Hex Casting | 0.11.3+ |
| Iron's Spells 'n Spellbooks | 1.20.1-3.15.2+ |
| Java | 17 |

模组会在 ISS 存在的情况下自动注册所有动作；若 ISS 缺失，模组会安全退出。

- **Mod ID**：`hexencrypt`
- **当前版本**：1.9.0
- **作者**：liquiHg

---

## ISS 法术 Hex 化

启动时，模组会扫描 ISS 注册表里所有已启用的法术，并为每一个注册一个 Hex 动作：

```
hexencrypt:iss_<spell_name>
```

例如：

```
hexencrypt:iss_fireball
hexencrypt:iss_heal
hexencrypt:iss_telekinesis
```

### 图案结构

图案由若干「段」拼接而成，每一段都是一种**官能团**（functional group），段之间用随机连接线接起来，最后接一段 hash 后缀以保证唯一性：

```
[ school ] — [ target ] — [ cast ] — [ hash suffix ]
  学派        目标类型     施法类型     hash 后缀
```

### 官能团表

**school（学派）**

| 学派 | 签名 |
|---|---|
| eldritch | `aedwwedewaqawqqaqq` |
| nature | `wqaqwawqaqw` |
| lightning | `qeaqadeedew` |
| ice / frost | `eadaedeadaedeadae` |
| blood | `edewqaqqqeeeeqeweweweweweddw` |
| evocation | `eeeaaeeedewwewqaaedwaeadwe` |
| holy | `adeaaedaqadeaaeda` |
| ender | `qdeddwaqwqqqwq` |
| fire | `aaqawawa` |
| fallback | `wdw` |

**target（目标类型）**

| 目标 | 签名 |
|---|---|
| self | `qaq` |
| other | `ede` |
| summon | `aqqa` |

**cast（施法类型）**

| 类型 | 签名 |
|---|---|
| none | `eqaa` |
| instant | `www` |
| continuous | `qqqqq` |
| long | `qqqqqaw` |

### 三级降级

因为「段与段拼接 + 保证图案不自交」并不总能成功，生成器按以下顺序尝试：

- **A 完整**：`school + target + cast + suffix`
- **B 降级**：舍弃 school，用 `target + cast + suffix`
- **C 兜底**：随机游走，仅保证唯一签名（会输出 WARN，语义丢失）

启动日志会输出每一级的数量统计，方便开发者排查。

### 学派映射

默认从 `ISSHexConfig`（`config/hexencrypt-iss-schools.toml`）读取每个法术的学派。若配置中没有条目，使用 `fallback`。

### 动作分派

生成图案之后，模组按以下规则挑选操作符：

| 条件 | 操作符 |
|---|---|
| 法术需要「简单目标」（只读 `TargetEntityCastData`） | `OpCastIronsSpellWithTarget` |
| `CastType == CONTINUOUS` | `OpCastContinuousIronsSpell` |
| 其他 | `OpCastIronsSpell` |

「需要简单目标」是通过 ASM 扫描法术类字节码自动判定的，无需手动配置。

---

## 法术解析台

**方块 ID**：`hexencrypt:spell_analysis_table`

### 打开方式

- 空手右键方块 → 打开 UI。
- 手持物品右键 → 把物品放入中央槽（一次一个，满槽时交换）。
- 空手潜行右键 → 取出中央槽物品。

### UI 布局

UI 中心有 4 个象限，每个象限是一张「画布」：

```
┌────────┬────────┐
│   Q0   │   Q1   │
├────────┼────────┤
│   Q2   │   Q3   │
└────────┴────────┘
```

中央槽放**要解析的法术源**（ISS 卷轴或 Hex 卷轴）。右侧媒质条显示当前媒质，媒质槽用于充能。

### 操作说明

| 操作 | 效果 | 消耗 |
|---|---|---|
| 左键任意象限（画布未就绪） | 铺满 4 个象限的画布 | 4 × 空白小卷轴 |
| 左键拖动（画布已就绪） | 绘制图案 | 1 媒质 / 边 |
| 右键 | 回退最后一条边 | 2 媒质 |
| Shift + 右键 | 把当前象限的图案入栈 | 0 |
| Shift + 左键 | 清空对应象限的栈和图案 | 100 媒质 |
| 中键 | 手动触发解析 | 0 |
| 拖到象限边缘 | 图案网格平移一格 | 20 媒质 |

**必须先铺满 4 个象限的画布才能开始绘制**。绘制完成后把图案入栈（Shift + 右键），当 3 个及以上象限都入栈后会自动尝试解析，也可以中键手动触发。

### 匹配分类

解析结果分三类：

| 分类 | 含义 | 结果 |
|---|---|---|
| **DEEP** | 前 3 个栈依次是 school + target + cast | 发解析成书 + 截断大卷轴 |
| **SHALLOW** | 前 3 个栈按顺序部分匹配 central 图案 | 发截断大卷轴 + 经验 |
| **MISHAP** | 有段不匹配，或 3 段都是官能团但顺序错误 | 未匹配栈变为垃圾 iota，模拟 Hex 施法失败反馈，关闭 UI |

**浅度经验公式**（x = 匹配到的边数）：

```
0  – 15    2x + 7
16 – 30    5x - 38
31 及以上  9x - 158
```

### 解析成功后的消耗

- DEEP / SHALLOW：4 个象限的画布被消耗。
- MISHAP：画布保留，玩家可以继续在原有画布上修改。

### 空签名

**2 点一边的图案（只有一条边，`anglesSignature()` 为空串）** 是否参与 SHALLOW 部分匹配，由配置 `allowEmptySignatureMatch` 决定：

- `true`（默认）：空签名视为匹配，可以触发 SHALLOW。
- `false`：空签名直接导致部分匹配失败，落入 MISHAP。

---

## 自定义操作符

除自动注册的 ISS 动作外，模组还注册了 3 个自定义动作。

### `set_target` — 设置强制目标

```
图案:  weaqawawaeweweweweweeewaqqqqqwewddaqaew
起笔:  south_east
```

**栈输入**：一个 `Vec3Iota`（栈顶）。

在指定坐标周围 3 格内搜索最近的 `LivingEntity`，把它设为下一次 ISS 施法的强制目标。之后无论玩家视线朝哪，`preCastTargetHelper` 都会被 Mixin 拦截，直接使用这个目标。

**用完即清**：目标被消费一次后自动失效，需要再次设置。

### `clear_target` — 清除强制目标

```
图案:  aedwewewewewewqwwdewwewadaewwwdewwewa
起笔:  west
```

无栈输入，立即清除当前玩家的强制目标，恢复默认的视线射线检测。

### `set_timbre` — 设置音色

```
图案:  eqqadaa
起笔:  north_west
```

**栈输入**：一个 `DoubleIota`（栈顶），音色索引。

设置玩家的画线音色，影响落笔和画线时的音效。索引 → 音色名映射见 [音色系统](#音色系统)。

---

## 音色系统

音色由 `set_timbre` 操作符设置，或直接在持久化数据里写 `hexencrypt:timbre`。

| 索引 | 音色 |
|---|---|
| 0 | default（Hex 原版画线音） |
| 1 | 钟 |
| 2 | 风铃 |
| 3 | 长笛 |
| 4 | 吉他 |
| 5 | 竖琴 |
| 6 | 木琴 |
| 7 | 铁木琴 |
| 8 | 牛铃 |
| 9 | 迪吉里杜管 |
| 10 | 电子叮 |
| 11 | 贝斯 |
| 12 | 军鼓 |
| 13 | 踩镲 |
| 14 | 芯片音 |
| 15 | 班卓琴 |

**音高规则不受影响**：直走 → 中音，拐弯 → 音高随转角变化，回退 → 低沉提示音。

音色状态由服务端持久化，通过 `SyncTimbrePacket` 同步到客户端，其他玩家听不到你的音色。

---

## 施法解锁

ISS 法术需要「解锁」才能正常使用。解锁方式是**通过 Hex 主动施放该法术**：

- 只有在 `CastingEnvironment` 是 **StaffCastEnv** 或 **CyipherCastEnv**（玩家手持法杖 / 画符主动绘制）时才算数。
- 法术环、饰品触发、造物被动、NPC 施法**不会**解锁。
- 首次成功施放时：
  - 写入玩家持久化数据 `hexencrypt:unlocked_spells`。
  - 发送解锁网络包，客户端缓存。
  - ActionBar 显示紫色提示。
  - 玩家周围生成主环 + 副环附魔台粒子。

### 客户端缓存

客户端缓存类为 `ClientSpellCache`，用于物品名称混淆显示（未解锁的 ISS 卷轴名称会被打码）。

---

## 命令

所有命令都在 `/hexencrypt` 命名空间下，需要**权限等级 2**（OP）。

### 解锁管理

| 命令 | 说明 |
|---|---|
| `/hexencrypt unlock <spell>` | 解锁指定法术。`<spell>` 是 ISS 法术 id（如 `irons_spellbooks:fireball`） |
| `/hexencrypt lock <spell>` | 锁定指定法术 |
| `/hexencrypt unlockall` | 解锁所有 ISS 法术，返回实际新解锁的数量 |
| `/hexencrypt lockall` | 清空所有解锁记录 |
| `/hexencrypt list` | 列出当前已解锁的法术名 |
| `/hexencrypt clear` | 同 `lockall`，清空解锁记录 |

### 卷轴发放

| 命令 | 说明 |
|---|---|
| `/hexencrypt scroll <spell>` | 给予玩家一张包含指定动作图案的小卷轴。`<spell>` 支持两种写法：`fireball`（自动补 `iss_` 前缀）或 `set_target` / `clear_target`（自定义动作直接写名） |
| `/hexencrypt scrollAll` | 给予玩家所有 `hexencrypt:*` 动作的卷轴 |

`scroll` 命令的 Tab 补全会自动列出所有 `hexencrypt:iss_*` 动作的短名（去掉 `iss_` 前缀）。

背包放不下的卷轴会掉落到玩家脚下。

---

## 配置

### `config/hexencrypt-common.toml`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enableEncryption` | boolean | `true` | 是否对 ISS 卷轴启用加密 |
| `unlockCost` | int | `1000` | 每次解锁消耗的媒质，0 = 无消耗 |
| `debugMode` | boolean | `false` | 是否开启调试日志 |
| `allowEmptySignatureMatch` | boolean | `true` | 2 点一边的空签名是否参与 SHALLOW 部分匹配 |

### `config/hexencrypt-iss-schools.toml`

为每个 ISS 法术名手动映射到学派。未配置的法术使用 `fallback`。映射表格式：

```toml
[spells]
fireball = "fire"
heal = "holy"
telekinesis = "eldritch"
# ...
```

---

## 常见问题

**Q：为什么我画的图案总是 MISHAP？**

A：先确认 4 个象限的画布是否都铺满。再检查前 3 个入栈的段是否是 school + target + cast 的正确顺序。如果顺序对但仍失败，可能是 `allowEmptySignatureMatch = false` 且你用了 2 点一边的图案。

**Q：ISS 法术的 Hex 图案怎么查？**

A：启动时日志会输出每个法术的图案生成结果。也可以在游戏里用 `/hexencrypt scroll <spell>` 拿一张对应卷轴查看图案。

**Q：为什么我给某个法术指定了学派，但图案没变？**

A：学派影响的是 school 段的**形状**，但 hash 后缀和连接线是随机的。要完全复现同一个图案，需要同样的 `mixSeed` 和同样的生成顺序。配置改动后重启即可重新生成。

**Q：为什么我在创造模式作弊给了自己一堆 ISS 卷轴，但法术还是不能用？**

A：这是设计意图。解锁只在通过 Hex 主动施放时发生，作弊获取不触发。可以用 `/hexencrypt unlock <spell>` 或 `/hexencrypt unlockall` 手动解锁。

**Q：`set_target` 设置的目标消失了吗？**

A：是的。目标被 `preCastTargetHelper` 消费一次后立即失效。如果需要连续施法命中同一目标，每次施法前都要重新画一遍 `set_target`。

**Q：MISHAP 之后我的图案去哪了？**

A：未匹配的栈被替换为 `GarbageIota`，匹配成功的部分保留。画布不受影响，可以继续在上面修改。UI 会被强制关闭。

**Q：`set_timbre` 的音色别人听得到吗？**

A：听不到。音色是玩家本地状态，由服务端持久化、只向本人同步。

**Q：`/hexencrypt scroll` 和 `/hexencrypt scrollAll` 有什么区别？**

A：`scroll` 给一张指定动作的卷轴；`scrollAll` 给所有本模组注册的动作各一张，适合一次性铺满背包做测试。

**Q：`/hexencrypt unlock` 和通过施法解锁有什么区别？**

A：效果一样，都会写入解锁记录并同步到客户端。区别是命令不需要玩家主动施法，用于调试或服务器管理。

---

## 许可

**All Rights Reserved.** 本模组保留所有权利。若需二次分发、整合包收录或代码引用，请先联系作者 liquiHg 获取授权。

---
---

# Hex Encryption (English)

**A bridge mod between Hex Casting × Iron's Spells 'n Spellbooks**

Turns every spell from Iron's Spells 'n Spellbooks (hereafter ISS) into a Hex action that can be drawn, cast, and has a unique pattern in Hex Casting. Players can draw patterns, push them onto the stack, and resolve them at the "Spell Analysis Table", ultimately "translating" ISS spells into Hex patterns — and unlocking the ISS spells themselves by casting them through Hex.

---

## Table of Contents

1. [Overview](#overview)
2. [Dependencies](#dependencies)
3. [ISS Spells as Hex](#iss-spells-as-hex)
4. [Spell Analysis Table](#spell-analysis-table)
5. [Custom Operators](#custom-operators)
6. [Timbre System](#timbre-system)
7. [Casting & Unlocking](#casting--unlocking)
8. [Commands](#commands)
9. [Configuration](#configuration)
10. [FAQ](#faq)
11. [License](#license)

---

## Overview

The mod does four main things:

- **Automatically registers a Hex action for every ISS spell.** Patterns are generated from a "functional group prefix + hash suffix", guaranteeing uniqueness and readability.
- **Provides a "Spell Analysis Table" block**, letting players "crack" ISS spell patterns by drawing.
- **Adds three custom Hex operators**: set target, clear target, and set timbre.
- **Unlocks ISS spells by casting through Hex**, caching the unlock state on the client and giving unlock feedback.

---

## Dependencies

| Dependency | Version requirement |
|---|---|
| Minecraft | 1.20.1 (`[1.20.1, 1.21)`) |
| Forge | 47+ |
| Hex Casting | 0.11.3+ |
| Iron's Spells 'n Spellbooks | 1.20.1-3.15.2+ |
| Java | 17 |

The mod automatically registers all actions when ISS is present; if ISS is missing, the mod exits safely.

- **Mod ID**: `hexencrypt`
- **Current version**: 1.9.0
- **Author**: liquiHg

---

## ISS Spells as Hex

At startup, the mod scans every enabled spell in the ISS registry and registers a Hex action for each one:

```
hexencrypt:iss_<spell_name>
```

For example:

```
hexencrypt:iss_fireball
hexencrypt:iss_heal
hexencrypt:iss_telekinesis
```

### Pattern Structure

A pattern is assembled from several "segments", each one a **functional group**. Segments are joined by random connector lines, and a hash suffix is appended at the end to guarantee uniqueness:

```
[ school ] — [ target ] — [ cast ] — [ hash suffix ]
   school     target type   cast type    hash suffix
```

### Functional Groups

**school**

| School | Signature |
|---|---|
| eldritch | `aedwwedewaqawqqaqq` |
| nature | `wqaqwawqaqw` |
| lightning | `qeaqadeedew` |
| ice / frost | `eadaedeadaedeadae` |
| blood | `edewqaqqqeeeeqeweweweweweddw` |
| evocation | `eeeaaeeedewwewqaaedwaeadwe` |
| holy | `adeaaedaqadeaaeda` |
| ender | `qdeddwaqwqqqwq` |
| fire | `aaqawawa` |
| fallback | `wdw` |

**target**

| Target | Signature |
|---|---|
| self | `qaq` |
| other | `ede` |
| summon | `aqqa` |

**cast**

| Type | Signature |
|---|---|
| none | `eqaa` |
| instant | `www` |
| continuous | `qqqqq` |
| long | `qqqqqaw` |

### Three-Level Fallback

Because "joining segments + guaranteeing the pattern never self-intersects" doesn't always succeed, the generator tries in the following order:

- **A (full)**: `school + target + cast + suffix`
- **B (degraded)**: drop school, use `target + cast + suffix`
- **C (last resort)**: random walk, guaranteeing only a unique signature (emits a WARN; semantics are lost)

The startup log prints count statistics for each level, making it easier for developers to diagnose issues.

### School Mapping

By default, each spell's school is read from `ISSHexConfig` (`config/hexencrypt-iss-schools.toml`). If a spell has no entry there, `fallback` is used.

### Action Dispatch

After generating the pattern, the mod picks an operator by the following rules:

| Condition | Operator |
|---|---|
| The spell needs a "simple target" (only reads `TargetEntityCastData`) | `OpCastIronsSpellWithTarget` |
| `CastType == CONTINUOUS` | `OpCastContinuousIronsSpell` |
| Otherwise | `OpCastIronsSpell` |

Whether a spell "needs a simple target" is determined automatically by ASM-scanning the spell class's bytecode — no manual configuration needed.

---

## Spell Analysis Table

**Block ID**: `hexencrypt:spell_analysis_table`

### Opening

- Right-click the block with an empty hand → opens the UI.
- Right-click with an item → places the item into the central slot (one at a time; swaps when the slot is full).
- Sneak + right-click with an empty hand → takes the item out of the central slot.

### UI Layout

The UI's center has 4 quadrants, each one a "canvas":

```
┌────────┬────────┐
│   Q0   │   Q1   │
├────────┼────────┤
│   Q2   │   Q3   │
└────────┴────────┘
```

The central slot holds the **spell source to analyze** (an ISS scroll or a Hex scroll). The media bar on the right shows current media, and the media slot is used for charging.

### Controls

| Action | Effect | Cost |
|---|---|---|
| Left-click any quadrant (canvas not ready) | Fills all 4 quadrants with canvas | 4 × blank small scroll |
| Left-click drag (canvas ready) | Draws the pattern | 1 media / edge |
| Right-click | Reverts the last edge | 2 media |
| Shift + right-click | Pushes that quadrant's pattern onto the stack | 0 |
| Shift + left-click | Clears that quadrant's stack and pattern | 100 media |
| Middle-click | Manually triggers resolution | 0 |
| Drag to a quadrant's edge | Pans the pattern grid by one cell | 20 media |

**All 4 quadrants must be filled with canvas before drawing can begin.** After drawing, push the pattern onto the stack (Shift + right-click). Once 3 or more quadrants have been pushed, resolution is attempted automatically; it can also be triggered manually with middle-click.

### Match Categories

Resolution results fall into three categories:

| Category | Meaning | Result |
|---|---|---|
| **DEEP** | The first 3 stacks are school + target + cast, in order | Gives a resolved book + truncated large scroll |
| **SHALLOW** | The first 3 stacks partially match the central pattern, in order | Gives a truncated large scroll + experience |
| **MISHAP** | A segment mismatches, or all 3 segments are functional groups but in the wrong order | Unmatched stacks become garbage iota, simulating Hex casting failure feedback; UI closes |

**Shallow experience formula** (x = number of matched edges):

```
0  – 15    2x + 7
16 – 30    5x - 38
31 and up  9x - 158
```

### Consumption on Success

- DEEP / SHALLOW: all 4 quadrants' canvases are consumed.
- MISHAP: canvases are kept, so the player can keep editing them.

### Empty Signature

Whether a **2-point, 1-edge pattern (a single edge, with an empty `anglesSignature()` string)** participates in SHALLOW partial matching is controlled by the `allowEmptySignatureMatch` config:

- `true` (default): empty signatures count as matches and can trigger SHALLOW.
- `false`: empty signatures cause partial matching to fail outright, falling into MISHAP.

---

## Custom Operators

Besides the auto-registered ISS actions, the mod registers 3 custom actions.

### `set_target` — Set Forced Target

```
Pattern: weaqawawaeweweweweweeewaqqqqqwewddaqaew
Start:   south_east
```

**Stack input**: one `Vec3Iota` (top of stack).

Searches for the nearest `LivingEntity` within 3 blocks of the given coordinates and sets it as the forced target for the next ISS cast. Afterwards, no matter where the player looks, `preCastTargetHelper` is intercepted by a Mixin and this target is used directly.

**Consumed on use**: the target is invalidated after being consumed once; it must be set again.

### `clear_target` — Clear Forced Target

```
Pattern: aedwewewewewewqwwdewwewadaewwwdewwewa
Start:   west
```

No stack input. Immediately clears the current player's forced target, restoring the default line-of-sight raycast.

### `set_timbre` — Set Timbre

```
Pattern: eqqadaa
Start:   north_west
```

**Stack input**: one `DoubleIota` (top of stack) — the timbre index.

Sets the player's line-drawing timbre, affecting the sound effects on stroke and while drawing. See [Timbre System](#timbre-system) for the index → timbre name mapping.

---

## Timbre System

The timbre is set by the `set_timbre` operator, or written directly into persistent data as `hexencrypt:timbre`.

| Index | Timbre |
|---|---|
| 0 | default (vanilla Hex line-drawing sound) |
| 1 | Bell |
| 2 | Chime |
| 3 | Flute |
| 4 | Guitar |
| 5 | Harp |
| 6 | Xylophone |
| 7 | Iron Xylophone |
| 8 | Cow Bell |
| 9 | Didgeridoo |
| 10 | Electronic Ding |
| 11 | Bass |
| 12 | Snare |
| 13 | Hi-hat |
| 14 | Chiptune |
| 15 | Banjo |

**Pitch rules are unaffected**: straight lines → mid pitch; turns → pitch varies with the turn angle; reverting → low cue sound.

Timbre state is persisted server-side and synced to the client via `SyncTimbrePacket`. Other players cannot hear your timbre.

---

## Casting & Unlocking

ISS spells must be "unlocked" before they can be used normally. The unlock method is **actively casting the spell through Hex**:

- Only counts when the `CastingEnvironment` is **StaffCastEnv** or **CyipherCastEnv** (player actively casting with a staff / drawing a cypher).
- Spell rings, trinket triggers, construct passives, and NPC casting do **not** unlock.
- On the first successful cast:
  - Writes `hexencrypt:unlocked_spells` to the player's persistent data.
  - Sends an unlock network packet; the client caches it.
  - Shows a purple ActionBar message.
  - Spawns main-ring + sub-ring enchanting table particles around the player.

### Client Cache

The client cache class is `ClientSpellCache`, used for obfuscated item name display (names of locked ISS scrolls are masked).

---

## Commands

All commands live under the `/hexencrypt` namespace and require **permission level 2** (OP).

### Unlock Management

| Command | Description |
|---|---|
| `/hexencrypt unlock <spell>` | Unlocks the specified spell. `<spell>` is an ISS spell id (e.g. `irons_spellbooks:fireball`) |
| `/hexencrypt lock <spell>` | Locks the specified spell |
| `/hexencrypt unlockall` | Unlocks all ISS spells; returns the number actually newly unlocked |
| `/hexencrypt lockall` | Clears all unlock records |
| `/hexencrypt list` | Lists currently unlocked spell names |
| `/hexencrypt clear` | Same as `lockall`; clears unlock records |

### Scroll Distribution

| Command | Description |
|---|---|
| `/hexencrypt scroll <spell>` | Gives the player a small scroll containing the specified action's pattern. `<spell>` accepts two forms: `fireball` (auto-prefixes `iss_`) or `set_target` / `clear_target` (custom actions, written directly) |
| `/hexencrypt scrollAll` | Gives the player a scroll for every `hexencrypt:*` action |

Tab completion for the `scroll` command automatically lists the short names of all `hexencrypt:iss_*` actions (with the `iss_` prefix stripped).

Scrolls that don't fit in the inventory drop at the player's feet.

---

## Configuration

### `config/hexencrypt-common.toml`

| Key | Type | Default | Description |
|---|---|---|---|
| `enableEncryption` | boolean | `true` | Whether to enable encryption for ISS scrolls |
| `unlockCost` | int | `1000` | Media consumed per unlock; 0 = no cost |
| `debugMode` | boolean | `false` | Whether to enable debug logging |
| `allowEmptySignatureMatch` | boolean | `true` | Whether 2-point, 1-edge empty signatures participate in SHALLOW partial matching |

### `config/hexencrypt-iss-schools.toml`

Manually maps each ISS spell name to a school. Spells without a configured entry use `fallback`. Mapping format:

```toml
[spells]
fireball = "fire"
heal = "holy"
telekinesis = "eldritch"
# ...
```

---

## FAQ

**Q: Why do my drawn patterns always end in MISHAP?**

A: First confirm all 4 quadrants are filled with canvas. Then check that the first 3 pushed segments are in the correct school + target + cast order. If the order is right but it still fails, you may have `allowEmptySignatureMatch = false` while using a 2-point, 1-edge pattern.

**Q: How do I look up an ISS spell's Hex pattern?**

A: The startup log prints the pattern generation result for every spell. In-game, you can also use `/hexencrypt scroll <spell>` to get a scroll with the corresponding pattern.

**Q: I assigned a school to a spell, but the pattern didn't change. Why?**

A: The school affects the **shape** of the school segment, but the hash suffix and connector lines are random. To reproduce the exact same pattern, you need the same `mixSeed` and the same generation order. Restart after changing the config to regenerate.

**Q: I cheated myself a bunch of ISS scrolls in creative, but the spells still don't work. Why?**

A: This is intentional. Unlocking only happens when actively casting through Hex; cheating doesn't trigger it. Use `/hexencrypt unlock <spell>` or `/hexencrypt unlockall` to unlock manually.

**Q: Is the target set by `set_target` gone?**

A: Yes. The target is invalidated immediately after being consumed once by `preCastTargetHelper`. If you need to hit the same target with consecutive casts, you must draw `set_target` again before each cast.

**Q: Where did my pattern go after a MISHAP?**

A: Unmatched stacks are replaced with `GarbageIota`; successfully matched parts are kept. The canvas is unaffected and can still be edited. The UI is force-closed.

**Q: Can other players hear my `set_timbre` timbre?**

A: No. Timbre is local player state, persisted server-side and synced only to the owner.

**Q: What's the difference between `/hexencrypt scroll` and `/hexencrypt scrollAll`?**

A: `scroll` gives one scroll for a specified action; `scrollAll` gives one scroll for every action registered by this mod — handy for filling your inventory in one go for testing.

**Q: What's the difference between `/hexencrypt unlock` and unlocking by casting?**

A: The effect is the same — both write the unlock record and sync it to the client. The difference is that the command doesn't require the player to actively cast; it's meant for debugging or server management.

---

## License

**All Rights Reserved.** This mod reserves all rights. For redistribution, modpack inclusion, or code references, please contact the author liquiHg for permission first.