# 已知问题与待办（制卡台 / 绑定 / 自动化装置）

> 2026-08-15 现状快照。服务端逻辑本轮已补齐，客户端 UI 与文案未跟上，以下按优先级排列。

## 1. 制卡台 GUI 仍是深色风格

[CardForgeScreen.java](src/client/java/itemalchemy/expansion/client/CardForgeScreen.java) 主题色未换：
`PANEL = 0xFF0D1117`、`SLOT_BG = 0xFF161C24`、`TEXT_MAIN = 0xFFE6F1FF` 均为深色系。

参考已完成的 `EmcConverterScreen`（MC 经典浅灰）统一改：
`PANEL = 0xFFC6C6C6`、`PANEL_LINE = 0xFF555555`、`SLOT_BG = 0xFF8B8B8B`、文字 `0xFF404040` 系。
`ModernButton.renderButton` 内的深色底 `0xFF1C2A3A / 0xFF141A22` 需同步换浅色。

## 2. 绑定功能缺玩家列表 / 模糊匹配（服务端已就绪，客户端未接）

- 服务端已加：`ACTION_REQUEST_PLAYERS = 8`（C2S 请求在线列表）、
  `PLAYERS_ID`（S2C 下发 `VarInt count + String[] names`，见 `CardForgeNetwork.sendPlayers`）。
- 客户端缺：
  - `CardForgeClientNetwork` 未注册 `PLAYERS_ID` 接收器、未加 `sendRequestPlayers()`；
  - `CardForgeScreen` 绑定页未做下拉候选列表（输入几个字模糊匹配、点击选中填入）。
- 参考实现：`EmcEmitterScreen` 的滚动列表 + `mouseClicked`/`mouseScrolled` 处理。

## 3. 解除关联按钮缺失（服务端已就绪，客户端未接）

- 服务端已加 `ACTION_UNLINK = 7`：共享余额全部回到槽 0 卡，右槽同组卡一并解除标记，删除共享账户。
- 客户端「组合」页只有「关联两卡 / 合并两卡」两个按钮，需加第三个「解除关联」
  （仅当槽 0 卡 `getLinkGroup() != null` 时可用/可见）。
- 解绑按钮已有（绑定页 `btnBind` 文案切换），无问题。

## 4. 绑定页排版重叠

`initOverride` 中：
- `singleField`/`totalField` 在 `top + 110`（高 16），`btnApplyLimits` 在 `top + 108`（高 18）——
  应用按钮与 `totalField`（`cx - 12` 起 40 宽）横向重叠。
- 建议布局：字段行 `top + 108`、按钮 `top + 128`，或按钮整体右移避开。

## 5. 物品栏格子未对齐

`drawBackgroundOverride` 直接用 `s.x / s.y` 画格子底框，原版槽位绘制有半像素偏移惯例；
另外盖第二槽用的硬编码 `this.x + 98, this.y + 44` 与槽位实际坐标强耦合，改槽位时会错位。
建议统一从 `this.handler.slots` 取坐标。

## 6. 新增 lang 键未写入语言文件

本轮服务端新增的 5 个键，`zh_cn.json` / `en_us.json` 均缺（运行时会显示原始键名）：

| 键 | 建议 zh_cn |
|----|-----------|
| `card_forge.link.bound_unsupported` | 绑定卡不支持关联，请先解除绑定 |
| `card_forge.merge.bound_unsupported` | 绑定卡不支持合并，请先解除绑定 |
| `card_forge.bind.linked` | 关联卡不支持绑定，请先解除关联 |
| `card_forge.unlink.not_linked` | 左槽卡未关联 |
| `card_forge.unlink.success` | 已解除关联，%s EMC 回到左槽卡 |

## 7. 转换器 / 输出器文字提示不足

- `EmcConverterScreen`：4 个输入槽与 1 个卡槽无用途标注，需加
  「上排：待转换物品 / 中间：EMC 卡（转换所得存入）」类提示行。
- `EmcEmitterScreen`：列表为空/加载中已有提示，但无「所选物品对所有打开者共享」说明。
- 建议直接在 GUI 绘制小字提示（`TEXT_DIM` 色），不新增贴图。

## 8. 本轮已完成（服务端，随本次提交）

- `ACTION_UNLINK`：解除关联（余额回槽 0 卡、删共享账户）。
- `ACTION_REQUEST_PLAYERS` + `PLAYERS_ID` S2C：在线玩家列表下发。
- 绑卡禁止关联 / 合并；关联卡禁止绑定（双向防冲突）。
- 绑定时卡内原有余额自动转入绑定玩家 EMC，避免余额凭空消失。
- 解除绑定时清空单次/总额限额并复位可见性。

## 9. 逻辑风险备忘

- `unlink` 只处理「右槽恰为同组卡」的情形；若同组另一张卡不在制卡台内，
  该卡 NBT 仍带失效的 `link_group`（账户已删，读到 0）。可接受，但后续可加孤儿组清理。
- `sendPlayers` 只发在线玩家；绑定离线玩家仍需手输全名（走 usercache）。
