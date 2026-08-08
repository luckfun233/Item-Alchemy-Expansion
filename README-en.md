# Item Alchemy Expansion

[![CurseForge](http://cf.way2muchnoise.eu/title_10292936.svg)](https://www.curseforge.com/minecraft/mc-mods/item-alchemy-expansion)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

English | [中文](README.md)

An addon for [Item Alchemy](https://github.com/Pitan76/item-alchemy) that adds NBT-aware item distinction, recipe-based auto-pricing, and shulker box support to the Alchemy Table.

## AI-Assisted Development Notice

This project used AI during development for code writing, refactoring and documentation. All AI-generated content has been manually tested to ensure it works properly. If you find any issues, feel free to report them through an issue.

## Requirements

- Fabric API
- [Item Alchemy](https://modrinth.com/mod/item-alchemy) (+ mcpitanlib)

### Optional

- [Cloth Config](https://modrinth.com/mod/cloth-config) — in-game config screen
- [Mod Menu](https://modrinth.com/mod/modmenu) — config screen entry point

## Building from source

```
git clone https://github.com/luckfun233/Item-Alchemy-Expansion.git
cd Item-Alchemy-Expansion/item-alchemy-expansion-1.20.1
gradlew build        # Windows
./gradlew build      # Linux/macOS
```

The compiled jar will be in `build/libs/`.

## Reporting issues

Open an issue on the [issue tracker](https://github.com/luckfun233/Item-Alchemy-Expansion/issues). Include the full log if reporting a crash.

## Credits

- **Pitan** — author of [item-alchemy](https://github.com/Pitan76/item-alchemy), the upstream mod this expands on
- **MisterPeModder** — author of [ShulkerBoxTooltip](https://github.com/MisterPeModder/ShulkerBoxTooltip); its preview interaction design inspired the built-in shulker preview
- **mezz** — author of [JustEnoughItems](https://github.com/mezz/JustEnoughItems); its recipe scanning and ingredient normalization approach informed the auto-pricing implementation

## License

MIT. See [LICENSE](LICENSE).
