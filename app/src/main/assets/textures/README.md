# 贴图放置说明

把美术资源（PNG）放进**本目录**（即 `app/src/main/assets/textures/`），文件名必须与下表完全一致，
放好后重新构建即可自动生效，**不需要改任何代码**。

## 缺图会怎样？

不会崩、也不会白屏。代码会为每张缺失的贴图自动画一个**程序化占位图**：

- 缺牌面 → 画一张白底圆角牌，上面写着点数和花色（红桃/方块/大王为红色）
- 缺牌背 → 画一张蓝色圆角牌
- 缺按钮 → 画一个金/绿色圆角条，文字照常显示
- 缺头像 → 画一个圆形色块 + 名字首字

所以你现在就可以直接构建、联机、打完一整局。想确认还缺哪些，进游戏点大厅左下角的
**「贴图清单」**按钮，会列出所有仍缺失的必需贴图名。

## 规格要求

| 项目 | 要求 |
|---|---|
| 格式 | PNG-24，带 Alpha 通道，sRGB |
| 尺寸档 | 提供 @1x / @2x / @3x 三档，或只给 @3x（代码会自动降采样，但不会放大） |
| 牌面基准尺寸 | 300 × 420 px（5:7） |
| 牌背 | 300 × 420 px |
| 桌面背景 | 横屏 1920 × 1080 px（或更高，等比） |
| 图标 | 48 / 96 / 144 px |
| 源文件 | 请一并提供 PSD / AI 分层源文件，方便后续改尺寸和改配色 |
| 文字 | **不要把说明性文字烤进图里**（牌面点数花色除外），界面文字由代码渲染，便于改文案和多语言 |

## 一、牌面（55 张，必需）

4 花色 × 13 点数 + 大小王 + 牌背。

花色代号：`s` 黑桃、`h` 红桃、`c` 梅花、`d` 方块。

```
card_s_3.png   card_h_3.png   card_c_3.png   card_d_3.png
card_s_4.png   ...            ...            ...
card_s_5.png
card_s_6.png
card_s_7.png
card_s_8.png
card_s_9.png
card_s_10.png      ← 注意 10 的后缀是两个字符 "10"
card_s_j.png       ← J / Q / K / A 一律小写
card_s_q.png
card_s_k.png
card_s_a.png
card_s_2.png
（h / c / d 同理，共 4 × 13 = 52 张）

card_joker_small.png   小王
card_joker_big.png     大王
card_back.png          牌背
```

> **点数后缀必须小写**（`card_s_j.png`，不是 `card_s_J.png`）。
> 原因：Android 资源名禁止大写字母，用小写则同一份文件既能放 `assets/`
> 也能放进 `res/drawable-nodpi/`。代码里给人看的牌面文字仍然是 `J`/`Q`/`K`/`A`，
> 两者是分开的。

## 一之二、通用背景的命名兼容

背景类贴图支持横/竖屏分文件的命名，代码会自动依次尝试，**不需要你为了配合代码改名**：

| 代码里请求的名字 | 实际会依次尝试的文件名 |
|---|---|
| `table_bg` | `table_bg.png` → `table_bg_land.png` → `table_bg_port.png` |
| `lobby_bg` | `lobby_bg.png` → `lobby_bg_land.png` → `lobby_bg_port.png` |
| `logo` | `logo.png` → `splash_logo.png` |

本项目锁定横屏，所以优先取 `_land`。

## 二、界面（必需）

```
table_bg.png            桌面背景（横屏 1920×1080）
logo.png                游戏 Logo（透明底，建议 1000×1000）
btn_primary_normal.png      主按钮常态（出牌 / 叫分 / 创建房间）建议做成 9-patch
btn_primary_pressed.png     主按钮按下态
btn_secondary_normal.png    次按钮常态（不出 / 提示 / 返回）
btn_secondary_pressed.png   次按钮按下态
panel_bg.png            半透明深色圆角面板底（结算页、列表项）
avatar_default.png      默认头像（正方形，代码按圆形裁剪绘制）
seat_frame_active.png   当前回合座位高亮框
badge_landlord.png      地主标记（建议 96×56，透明底）
card_selected_overlay.png   牌被选中时的描边/发光效果（与牌面同尺寸）
icon_clock_ring.png     倒计时环底图（正方形，透明底）
```

## 三、界面（可选，缺了不影响游玩）

```
btn_primary_disabled.png    主按钮禁用态
btn_small_normal.png        小按钮常态
btn_small_pressed.png       小按钮按下态
input_bg.png                输入框底（昵称 / IP）
seat_frame_normal.png       普通座位框
avatar_frame_landlord.png   地主专属头像金框
badge_farmer.png            农民标记
card_count_bg.png           「剩 N 张」角标底（建议 120×64）
slot_hint_highlight.png     「提示」功能选中的候选牌高亮框
last_play_marker.png        上家出牌位置的标记
icon_coin.png               金币/分数图标
```

## 四、特效帧序列（可选）

按帧编号命名，代码会按名字顺序播放：

```
fx_bomb_01.png … fx_bomb_12.png          炸弹爆炸，12 帧
fx_rocket_01.png … fx_rocket_10.png      王炸火箭，10 帧
fx_spring_banner.png                     春天横幅
fx_anti_spring_banner.png                反春横幅
fx_multiplier_x2.png                     加倍 ×2 标
fx_multiplier_x4.png                     加倍 ×4 标
fx_win.png                               胜利横幅
fx_lose.png                              失败横幅
```

## 五、音效（可选，放在 `app/src/main/assets/sounds/`）

统一用 OGG Vorbis（`.ogg`），文件名固定：

```
deal.ogg        发牌
select.ogg      选牌
play.ogg        出牌
pass.ogg        不出
bomb.ogg        炸弹
rocket.ogg      王炸
click.ogg       按钮点击
win.ogg         胜利
lose.ogg        失败
your_turn.ogg   轮到你
landlord.ogg    地主确定
```

音效缺失时对应播放调用会被静默忽略，不会报错。

---

## 六、当前代码**实际会渲染**哪些资产（重要，避免做完的美术没用上）

下面这张表是截至当前版本的实际情况。**「未接线」的意思是资源放进去了也不会显示**，
不是命名问题，而是渲染代码还没写。缺图不会报错、不会崩，只是被忽略。

| 资产 | 状态 |
|---|---|
| `card_*`（54 张）、`card_back` | ✅ 已渲染 |
| `table_bg`、`lobby_bg`、`logo` | ✅ 已渲染（含上文的名字别名兼容） |
| `btn_primary_normal`、`btn_secondary_normal` | ✅ 已渲染 |
| `panel_bg` | ✅ 已渲染 |
| `avatar_default`、`badge_landlord`、`card_count_bg`、`icon_clock_ring` | ✅ 已渲染 |
| `btn_*_pressed`、`btn_primary_disabled` | ❌ 未接线（按钮目前**没有按下反馈**，也没有禁用态） |
| `btn_small_*` | ❌ 未接线（小按钮目前复用主按钮底图） |
| `seat_frame_normal` / `seat_frame_active` / `seat_frame_active_glow` | ❌ 未接线（座位高亮目前是代码画的圆环） |
| `avatar_frame_landlord` | ❌ 未接线 |
| `badge_farmer` | ❌ 未接线（只画了地主标） |
| `card_selected_overlay` | ❌ 未接线（选牌目前是代码画的描边） |
| `slot_hint_highlight` | ❌ 未接线 |
| `last_play_marker` | ❌ 未接线 |
| `icon_coin`、`icon_rank`、`icon_clock_ring_bg` | ❌ 未接线 |
| `input_bg`、`room_item_bg`、`scrollbar`、`tab_normal`、`tab_selected` | ❌ 未接线（大厅目前用 `panel_bg` 画框） |
| `num_0` … `num_9` | ❌ 未接线（倒计时数字目前用系统字体渲染） |
| `fx_*`（炸弹 12 帧 / 火箭 10 帧 / 春天 / 反春 / 加倍 ×2 ×4 / 胜负横幅 / 发牌拖尾） | ❌ **完全未接线**（没有特效播放器） |
| `ic_action_*` | ❌ 未接线（按钮目前是「底图 + 文字」，没用图标） |

> 上面标 ❌ 的都需要我补渲染代码才能显示。想做的话告诉我优先做哪些。
