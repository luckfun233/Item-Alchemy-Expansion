# Item Alchemy Expansion — 1.20.1 → 1.19.2 移植指南

> 目标项目：`item-alchemy-expansion-1.19.2/`（MC 1.19.2 / Fabric / Java 17）
> 上游同步基准：1.20.1 分支 `item-alchemy-expansion-1.20.1/`（mod_version 1.2.2）
> 本文档记录移植状态、实测 API 差异、构建方式与剩余工作，接手时先读本文。

## 目录

- [一、项目背景与目标](#一项目背景与目标)
- [二、当前移植进度总览](#二当前移植进度总览)
- [三、环境与依赖配置](#三环境与依赖配置)
- [四、1.19.2 与 1.20.1 实测 API 差异](#四1192-与-1201-实测-api-差异)
- [五、文件对照](#五文件对照)
- [六、构建与验证](#六构建与验证)
- [七、下一步 TODO](#七下一步-todo)
- [八、参考资料](#八参考资料)

## 一、项目背景与目标

Item Alchemy Expansion 是 Item Alchemy 的附属模组：炼金台 NBT 变体区分、药水/潜影盒支持、
精确与自动定价，以及 1.20.1 后期新增的 EMC 卡、制卡台、EMC 分解器/构物器等自动装置。

1.19.2 分支早期只同步到 1.20.1 的 **1.1.1** 状态（筛选按钮、精确 EMC、潜影盒预览、重新定价
对话框等），缺少此后 1.20.1 的全部新功能。本次移植目标是把这些功能补齐到 1.19.2。

**为什么不能直接用 1.20.1 的 jar**：1.20.1 编译产物引用了 1.20 专属 intermediary 编号
（如 `class_342.method_46426`、`class_7919`、`class_4185$class_7840`），放进 1.19.2 必然
`NoSuchMethodError`。详见工作区根目录 `1.19.2-crash-analysis.md`。

## 二、当前移植进度总览

### 2.1 已完成（已提交）

提交 `d345882 feat: 同步 1.20.1 的 EMC 卡与自动装置服务端实现`（62 文件，+4151 行）。

| 模块 | 1.19.2 文件 | 状态 |
|------|------------|------|
| EMC 卡物品 | `item/EmcCardItem.java`、`item/IAExpItems.java` | 完成 |
| 制卡台 | `block/CardForgeBlock{,Entity,Blocks}.java`、`gui/CardForgeScreenHandler{,s}.java` | 完成（服务端） |
| EMC 分解器 | `block/EmcConverterBlock{,Entity}.java`、`block/EmcAutoBlocks.java`、`gui/EmcConverterScreenHandler{,s}.java` | 完成（服务端） |
| EMC 构物器 | `block/EmcEmitterBlock{,Entity}.java`、`gui/EmcEmitterScreenHandler{,s}.java` | 完成（服务端） |
| 卡网络层 | `network/EmcCardNetwork`、`CardForgeNetwork`、`EmcAutoNetwork` | 完成 |
| 卡余额/账户 | `network/EmcCardBalanceUtil`、`CardAccountStore`、`PlayerEmcUtil` | 完成 |
| 权限门禁 | `IAExpPermissions.java` | 完成 |
| 命令 | `command/ReloadCommand.java`、`IAExpCommand`（顶层权限 + reload） | 完成 |
| 配置 | `config/IAExpConfig`（v10 → v16）、`IAExpConfigHolder`（升级 11–16） | 完成 |
| EMC 查询特判 | `mixin/MixinEMCManager`（EMC 卡：卡本身 + 卡内存储） | 完成 |
| 饱和运算 | `nbt/ShulkerBoxSupport#saturated{Multiply,Add}` | 完成 |
| 资源 | 方块模型/blockstate/贴图/GUI 贴图/合成配方/中英文语言文件 | 完成 |
| 反 DoS/防绕限 | `SetEmcNetwork`（权限门禁 + `clampPrealloc`） | 完成 |

`javac` 全量校验：**73 个源文件 0 错误**（提交时的可编译状态）。

### 2.2 已完成（客户端，本次提交）

| 文件 | 说明 |
|------|------|
| `client/EmcCardClientNetwork.java` | C2S 充入/拿取/配置 + S2C 打开 GUI |
| `client/CardForgeClientNetwork.java` | 制卡台动作 + 在线玩家列表 |
| `client/EmcAutoClientNetwork.java` | 构物器列表/所选/余额 + 配置同步 |
| `client/EmcCardMainScreen.java` 等 5 个 | EMC 卡菜单/充入/拿取/交易记录/快捷充能配置 |
| `client/EmcConverterScreen.java` | 分解器容器界面（纯代码绘制） |
| `client/EmcEmitterScreen.java` | 构物器容器界面（左列表 + 右背包） |
| `ItemAlchemyExpansionClient.java` | 客户端注册：3 个 S2C 接收器 + 3 个 `HandledScreens.register` |
| `compat/clothconfig/IAExpClothConfigScreen.java` | 新增 2 个 OP 开关 + Automation 分类（3 项） |

**制卡台服务端**（方块/容器/网络）与**客户端界面**均已完成，GUI 可正常打开。

### 2.3 尚未开始

- 运行时验证（进游戏跑一遍：卡充入/拿取/关联/绑定、分解器转换、构物器喷出、权限门禁）
- `MODRINTH.md` 的 1.19.2 发布说明（`fabric.mod.json` 版本随 `mod_version` 展开，现为 1.2.2）

## 三、环境与依赖配置

### 3.1 gradle.properties（现状）

```properties
minecraft_version=1.19.2
yarn_mappings=1.19.2+build.28
loader_version=0.19.3
loom_version=1.15.5
mod_version=1.2.2
fabric_version=0.77.0+1.19.2
itemalchemy_version=1.3.3
mcpitanlib_version=3.7.1
cloth_config_version=8.3.134
modmenu_version=4.2.0-beta.2
```

### 3.2 依赖形态（与 1.21.x 分支不同）

`maven.pitan76.net` 已被 Cloudflare 拦成 403，依赖 jar 内置在 `libs/`：

- `libs/itemalchemy-1.3.3.jar`、`libs/mcpitanlib-fabric-1.19.2-3.7.1.jar`（附 `libs/licenses/` 与 SHA-256）
- 构建用 `modImplementation files("libs/...")`，jar 依赖不读 POM，因此 mcpitanlib 原本的传递依赖需在 `build.gradle` 显式声明

**注意上游版本比 1.20.1 分支新**：1.19.2 用 itemalchemy **1.3.3** + mcpitanlib **3.7.1**，
而 1.20.1 用 itemalchemy 1.1.3 + mcpitanlib 3.3.2。1.3.3 的 `ServerState.of` 只接受
`MCServer`（不再接受 `MinecraftServer`），因此 `network/PlayerEmcUtil` 用反射同时适配两版。

**architectury 不在依赖里但运行时需要**：mcpitanlib 的 `CreativeTabBuilder.build()` 内部调用
`dev.architectury.registry.CreativeTabRegistry`。1.19.2 玩家实例里通常已有
`architectury-6.6.92-fabric.jar`（itemalchemy 自己也要用它注册物品组），故本模组未声明该依赖。

### 3.3 构建命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd item-alchemy-expansion-1.19.2
.\gradlew.bat build -x test --no-daemon --console=plain
```

## 四、1.19.2 与 1.20.1 实测 API 差异

移植时逐条踩过，**不要把 1.20.1 的写法直接抄过来**：

### 4.1 注册表

| 1.20.1 | 1.19.2 |
|--------|--------|
| `net.minecraft.registry.Registries.ITEM` | `net.minecraft.util.registry.Registry.ITEM` |
| `net.minecraft.registry.Registry.register` | `net.minecraft.util.registry.Registry.register` |
| `Registries.BLOCK` / `BLOCK_ENTITY_TYPE` / `SCREEN_HANDLER` | `Registry.BLOCK` / `BLOCK_ENTITY_TYPE` / `SCREEN_HANDLER` |

`BlockEntityType.Builder.create(factory, blocks...).build(null)` 两版一致（参数类型是
`com.mojang.datafixers.types.Type`）。

### 4.2 物品组（创造模式物品栏）

1.19.2 **没有** `fabric-item-groups-v1` 的 `ItemGroupEvents`（1.20 才有）。
mcpitanlib 的 `CreativeTabManager.addStack(...)` 依赖 `allRegister()`，而它在 mcpitanlib 自身
init 时已执行，**后注册的条目不会生效**。因此方块物品改用原版机制：

```java
new BlockItem(BLOCK, new Item.Settings().maxCount(64).group(ItemGroups.ITEM_ALCHEMY.build()))
```

`CreativeTabBuilder.build()` 幂等（`itemGroupMap` 命中即返回缓存），可安全重复调用。
副作用：`automationEnabled=false` 时自动装置仍会出现在物品栏（1.20.1 靠事件回调动态隐藏），
开关变更需重启才反映到物品栏。

### 4.3 客户端渲染（无 `DrawContext`）

1.19.2 没有 `net.minecraft.client.gui.DrawContext`（1.20 引入），所有绘制走 `MatrixStack`：

| 1.20.1 | 1.19.2 |
|--------|--------|
| `render(DrawContext, int, int, float)` | `render(MatrixStack, int, int, float)` + `renderBackground(matrices)` |
| `context.fill(...)` | `DrawableHelper.fill(matrices, ...)` |
| `context.fillGradient(...)` | `DrawableHelper.fillGradient` 是 **protected**，跨包不可用 → 自绘逐行 `fill` |
| `context.drawText(tr, t, x, y, c, shadow)` | `tr.drawWithShadow(matrices, t, x, y, c)` / `tr.draw(...)` |
| `context.drawCenteredTextWithShadow(tr, t, cx, y, c)` | `DrawableHelper.drawCenteredTextWithShadow(matrices, tr, t.asOrderedText(), cx, y, c)` |
| `context.drawTexture(tex, x, y, u, v, w, h)` | `DrawableHelper.drawTexture(matrices, x, y, 0, u, v, w, h, texW, texH)` |
| `context.drawItem(stack, x, y)` | 见下方 `drawItemIcon`（需模型视图矩阵） |
| `context.enableScissor(...)` | `DrawableHelper.enableScissor(...)` |
| `context.drawTooltip(...)` | `this.renderTooltip(matrices, ...)` |
| `context.getMatrices().xxx()` | 直接对传入的 `MatrixStack` 操作 |

物品图标（1.19.2 必须手动套模型视图矩阵，仓库内既有多处同款实现）：

```java
private static void drawItemIcon(MatrixStack matrices, ItemStack stack, int x, int y) {
    RenderSystem.getModelViewStack().push();
    RenderSystem.getModelViewStack().multiplyPositionMatrix(matrices.peek().getPositionMatrix());
    RenderSystem.applyModelViewMatrix();
    try {
        MinecraftClient.getInstance().getItemRenderer().renderGuiItemIcon(stack, x, y);
    } finally {
        RenderSystem.getModelViewStack().pop();
        RenderSystem.applyModelViewMatrix();
    }
}
```

### 4.4 mcpitanlib 3.7.1 客户端/容器 API（与 3.3.2 同名，已 javap 核对）

- `SimpleHandledScreen`：`initOverride()`、`drawBackgroundOverride(DrawBackgroundArgs)`、
  `renderOverride(RenderArgs)`、`drawForegroundOverride(DrawForegroundArgs)`、`removedOverride()`、
  `closeOverride()`；字段 `x/y/width/height/handler/textRenderer/itemRenderer`
- `DrawObjectDM`：1.19.2 是 `getStack()`（MatrixStack），1.20.1 是 `getContext()`（DrawContext）
- `SimpleScreenHandler`：构造 `(ScreenHandlerType, CreateMenuEvent)`；`quickMoveOverride(Player, int)`；
  `addPlayerMainInventorySlots` / `addPlayerHotbarSlots` / `callInsertItem` 均存在；
  加槽用原版 `addSlot(Slot)`（`SimpleScreenHandler` 里没有 `addSlot(Slot)` 重载，靠继承原版方法）
- `SimpleScreenHandlerTypeBuilder<T>(Factory)` + `build()` 存在
- `CompatBlockEntity`、`ExtendBlockEntityTicker<T>`、`ExtendBlockEntityProvider`、
  `CompatProperties.FACING`、`CompatBlockMapCodecUtil.createCodec(...)` 均存在
- `ServerPlayNetworking.registerGlobalReceiver(Identifier, (server, player, handler, buf, sender) -> ...)`
  仍是旧（Identifier）API，写法与 1.20.1 相同

### 4.5 其它

- `ButtonWidget` 只有旧构造器 `new ButtonWidget(x, y, w, h, Text, callback)`，没有 `builder()` / `.tooltip(Tooltip)`
- `TextFieldWidget` 没有 `getX()/getY()`，用 `ClickableWidget` 的 public 字段 `x`/`y`
- `ItemStack.getNbt()/getOrCreateNbt()` 仍可用（1.19.2 与 1.20.1 同为 NBT 体系，无 Data Components）
- `RecipeManager.values()` 返回 `Collection<Recipe<?>>`、`Recipe.getOutput()` 无 registry 参数（与 1.20.1 的 `getOutput(DynamicRegistryManager)` 不同）
- `MinecraftServer#getRecipeManager().setRecipes(...)` 可用于运行时增删配方（自动装置总开关）

## 五、文件对照

### 5.1 新增（main，1.20.1 → 1.19.2 基本 1:1，仅注册表 API 不同）

```
item/{EmcCardItem,IAExpItems}.java
block/{CardForgeBlock,CardForgeBlockEntity,CardForgeBlocks,EmcAutoBlocks,
       EmcConverterBlock,EmcConverterBlockEntity,EmcEmitterBlock,EmcEmitterBlockEntity}.java
gui/{CardForgeScreenHandler,CardForgeScreenHandlers,EmcConverterScreenHandler,
      EmcConverterScreenHandlers,EmcEmitterScreenHandler,EmcEmitterScreenHandlers}.java
network/{EmcCardNetwork,EmcCardBalanceUtil,CardAccountStore,PlayerEmcUtil,
          CardForgeNetwork,EmcAutoNetwork}.java
command/ReloadCommand.java
IAExpPermissions.java
```

### 5.2 新增（client）

```
EmcCardClientNetwork / CardForgeClientNetwork / EmcAutoClientNetwork
EmcCardMainScreen / EmcCardDepositScreen / EmcCardWithdrawScreen / EmcCardLogScreen / EmcCardConfigScreen
CardForgeScreen / EmcConverterScreen / EmcEmitterScreen
```

### 5.3 修改（既有文件）

| 文件 | 改动 |
|------|------|
| `ItemAlchemyExpansion.java` | 注册物品/方块/容器/网络、卡账户 tick 与存档、`syncAutomationRecipes` |
| `ItemAlchemyExpansionClient.java` | 3 个 S2C 接收器 + 3 个 Screen 注册 |
| `config/IAExpConfig{,Holder}.java` | v16、`AutomationMode`、5 个新字段、升级步骤 11–16 |
| `command/IAExpCommand{,RepriceCommand}.java` | 顶层权限门禁 + `reload` 子命令 |
| `network/SetEmcNetwork.java` | 改价/重定价权限门禁 + 预分配钳制 |
| `mixin/MixinEMCManager.java` | EMC 卡特判（卡本身 + 卡内存储） |
| `nbt/ShulkerBoxSupport.java` | 饱和乘/加，防溢出刷 EMC |
| `compat/clothconfig/IAExpClothConfigScreen.java` | 2 个 OP 开关 + Automation 分类 + 配置同步 |

## 六、构建与验证

### 6.1 沙箱环境的已知阻塞（重要）

在受限沙箱（DSH `workspace-write`）下，**JVM 的 `Files.isWritable` 对任何路径都返回 false**
（实测：工作区内、`%TEMP%`、工作区外全部为 false，但实际写入是成功的）。Loom 的
tiny-remapper 用该检查判断输出 jar 可写，于是 `remapJar` / `remapSourcesJar` 必然失败：

```
Execution failed for task ':remapJar'.
> Failed to remap ...\build\libs\itemalchemy-expansion-1.1.1.jar,
  java.io.IOException: the jar file ... can't be written
  at net.fabricmc.tinyremapper.OutputConsumerPath.<init>(OutputConsumerPath.java:102)
```

这不是 ACL 问题（逐路径修复无效），**必须在完整文件访问下构建**（届时 `~/.gradle` 也可写）。

### 6.2 快速编译校验（不需要 Gradle remap）

Gradle 只用来导出一次 classpath，之后用 `javac` 直接校验，秒级出结果。两个脚本已随仓库提交（`tools/`）：

1. 导出 classpath（需要一次完整文件访问权限的 Gradle 运行）：
   ```powershell
   cd item-alchemy-expansion-1.19.2
   .\gradlew.bat iaexpDumpCp --no-daemon --offline -I tools\dump-classpath.init.gradle
   # 产出项目根下 iaexp-classpath.txt（127 个条目，约 25 KB；勿提交）
   ```
2. 用 `javac` 编译全部源码（工作区内即可，无需额外权限）：
   ```powershell
   pwsh -NoProfile -File tools\javac-check.ps1
   # 可选 -OutDir / -ArgFile 指定不同输出路径，便于多进程并行校验
   ```
   脚本把源文件列表与 classpath 写进 `@argfile` 后调用 JDK 21 的 `javac --release 17 -proc:none`，
   输出 `javac exit=0  sources=N` 即通过。注意 argfile 里**不要给路径加引号**
   （javac 的 argfile 解析会把引号当作参数的一部分，报 `invalid flag`）。

### 6.3 建议的验证顺序

1. `javac` 全量 0 错误
2. 完整权限下 `gradlew build -x test` 通过
3. 进游戏：`/itemalchemy-expansion reload`、EMC 卡充入/拿取、制卡台关联/绑定/合并、
   分解器（漏斗 + 红石）转换、构物器选择物品并喷出、非 OP 玩家改价/命令被拒

## 七、下一步 TODO

1. 提交客户端部分（见 §2.2），确认 `javac` 与 `gradlew build` 均通过
2. 运行时验证 §6.3 的全部条目；重点回归 EMC 卡特判与 `MixinEMCManager` 的优先级顺序
3. 物品栏行为差异：自动装置总开关关闭时仍显示在物品栏（§4.2），如需与 1.20.1 一致，
   需自建 `ItemGroup` 或用 mcpitanlib 的注册时机重做
4. 版本号与发布说明：`gradle.properties` 的 `mod_version`、`MODRINTH.md`
5. 若上游 itemalchemy/mcpitanlib 与 1.20.1 分支继续分叉，注意 §3.2 的两版 API 漂移
   （`ServerState.of` 已用反射兼容；新增调用点同样需要核对）

## 八、参考资料

| 参考 | 用途 |
|------|------|
| `../research/item-alchemy-main/` | 上游 Item Alchemy 源码（1.3.3，与 1.19.2 依赖同版） |
| `../research/MCPitanLib-1.21.1/` | mcpitanlib 新版源码，查 midohra/registry 封装的行为 |
| `../research/ShulkerBoxTooltip-1.20.x/` | 潜影盒预览实现参考 |
| `../1.19.2-crash-analysis.md` | 「跨版本互拷 jar → NoSuchMethodError」的完整证据链 |
| `../PORTING-1.21.1.md` | 1.21.1 分支的移植指南（Data Components 体系，可对照 1.19.2 的 NBT 体系） |
| `../item-alchemy-expansion-1.20.1/` | 本次移植的同步基准（mod_version 1.2.2） |

本地快速查 API 是否存在（无需反编译工具）：

```powershell
$M='libs\mcpitanlib-fabric-1.19.2-3.7.1.jar'; $I='libs\itemalchemy-1.3.3.jar'
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -cp "$M;$I" net.pitan76.mcpitanlib.api.gui.SimpleScreenHandler
```
