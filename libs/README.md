# libs

`maven.pitan76.net` 已被 Cloudflare 拦截（对 CI 与普通客户端一律返回 403 `Just a moment...`），GitHub Actions 无法再从该 maven 解析依赖，
因此把下面两个上游 jar 随仓库分发，构建改为 `files("libs/...")` 本地引用（见 `build.gradle`）。

| 文件 | 上游 | 版本 | 许可 |
|------|------|------|------|
| `itemalchemy-1.4.1.jar` | [Pitan76/item-alchemy](https://github.com/Pitan76/item-alchemy) | 1.4.1（多版本通用 jar，`minecraft: *`） | MIT，见 `licenses/itemalchemy-LICENSE.txt` |
| `mcpitanlib-4.0.9-1.21.4-fabric.jar` | [Pitan76/MCPitanLib](https://github.com/Pitan76/MCPitanLib) | 4.0.9（1.21.4 构建） | MIT，见 `licenses/mcpitanlib-LICENSE.txt` |

- 两个 jar **未做任何修改**；MIT 允许二进制再分发，版权与许可声明已随附于 `licenses/`。
- SHA-256：
  - `itemalchemy-1.4.1.jar` — `5E9945E6BAF1375D123E7A6AD796536563EBB3C6C8B31FD6B5B7131F3A1D8D13`
  - `mcpitanlib-4.0.9-1.21.4-fabric.jar` — `DDAF1401EC67DCE124568CC312A31B65485F41CBEB09E4EEFB6FA4705DE7FF24`
- mcpitanlib 4.x 不再依赖 architectury（3.x 需要，且其 `fabric.mod.json` 声明了 `depends architectury`）。
- 更新方式：从上游 release 取到新 jar 覆盖同名文件，同步改 `gradle.properties` 里的版本号与本文件的哈希。
