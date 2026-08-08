# Item Alchemy Expansion

[![CurseForge](http://cf.way2muchnoise.eu/title_10292936.svg)](https://www.curseforge.com/minecraft/mc-mods/item-alchemy-expansion)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[English](README-en.md) | 中文

Item Alchemy 的扩展模组，为炼金桌添加 NBT 物品区分、基于配方的自动定价和潜影盒支持。

## AI 开发声明

本项目在开发过程中使用了 AI 参与代码编写、重构与文档生成。AI 产出的内容均经过人工测试验证以确保功能正常。若发现问题，欢迎通过 issue 反馈。

## 依赖

- Fabric API
- [Item Alchemy](https://modrinth.com/mod/item-alchemy)（含 mcpitanlib）

### 可选

- [Cloth Config](https://modrinth.com/mod/cloth-config) — 游戏内配置界面
- [Mod Menu](https://modrinth.com/mod/modmenu) — 配置界面入口

## 从源码构建

```
git clone https://github.com/luckfun233/Item-Alchemy-Expansion.git
cd Item-Alchemy-Expansion/item-alchemy-expansion-1.20.1
gradlew build        # Windows
./gradlew build      # Linux/macOS
```

编译产物在 `build/libs/`。

## 问题反馈

请在 [issue tracker](https://github.com/luckfun233/Item-Alchemy-Expansion/issues) 提交问题，崩溃报告请附上完整日志。

## Credits

- **Pitan** — author of [item-alchemy](https://github.com/Pitan76/item-alchemy), the upstream mod this expands on
- **MisterPeModder** — author of [ShulkerBoxTooltip](https://github.com/MisterPeModder/ShulkerBoxTooltip); its preview interaction design inspired the built-in shulker preview
- **mezz** — author of [JustEnoughItems](https://github.com/mezz/JustEnoughItems); its recipe scanning and ingredient normalization approach informed the auto-pricing implementation

## License

MIT，详见 [LICENSE](LICENSE)。
