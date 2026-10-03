# libs

`maven.pitan76.net` 已被 Cloudflare 拦截（对 CI 与普通客户端一律返回 403 `Just a moment...`），GitHub Actions 无法再从该 maven 解析依赖，
因此把下面两个上游 jar 随仓库分发，构建改为 `files("libs/...")` 本地引用（见 `build.gradle`）。

| 文件 | 上游 | 版本 | 许可 |
|------|------|------|------|
| `itemalchemy-1.1.3.jar` | [Pitan76/item-alchemy](https://github.com/Pitan76/item-alchemy) | 1.1.3 | MIT，见 `licenses/itemalchemy-LICENSE.txt` |
| `mcpitanlib-fabric+1.20.1-3.3.2.jar` | [Pitan76/MCPitanLib](https://github.com/Pitan76/MCPitanLib) | 3.3.2-1.20.1-fabric | MIT，见 `licenses/mcpitanlib-LICENSE.txt` |

- 两个 jar **未做任何修改**；MIT 允许二进制再分发，版权与许可声明已随附于 `licenses/`。
- SHA-256：
  - `itemalchemy-1.1.3.jar` — `4CD6C3B04FBD4BEC42AB341CB6222DF527A3BA847EC7C0094AF52C65BC079FF7`
  - `mcpitanlib-fabric+1.20.1-3.3.2.jar` — `E01ABE0CEA902F73165B057E231785ADA2F9B257AFDDFD37EB3055255AEE6127`
- 更新方式：从上游 release 取到新 jar 覆盖同名文件，同步改 `gradle.properties` 里的版本号与本文件的哈希（mcpitanlib 3.6.5 的 POM 曾损坏，勿用）。
