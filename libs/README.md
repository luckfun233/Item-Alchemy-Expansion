# libs

`maven.pitan76.net` 已被 Cloudflare 拦截（对 CI 与普通客户端一律返回 403 `Just a moment...`），GitHub Actions 无法再从该 maven 解析依赖，
因此把下面两个上游 jar 随仓库分发，构建改为 `files("libs/...")` 本地引用（见 `build.gradle`）。

| 文件 | 上游 | 版本 | 许可 |
|------|------|------|------|
| `itemalchemy-1.3.3-SNAPSHOT.jar` | [Pitan76/item-alchemy](https://github.com/Pitan76/item-alchemy) | 1.3.3-SNAPSHOT（上游源码本地构建） | MIT，见 `licenses/itemalchemy-LICENSE.txt` |
| `mcpitanlib-fabric.1.21.1-3.7.2.jar` | [Pitan76/MCPitanLib](https://github.com/Pitan76/MCPitanLib) | 3.7.2 | MIT，见 `licenses/mcpitanlib-LICENSE.txt` |

- 两个 jar **未做任何修改**；MIT 允许二进制再分发，版权与许可声明已随附于 `licenses/`。
- SHA-256：
  - `itemalchemy-1.3.3-SNAPSHOT.jar` — `23827FC94D1D6B4E800F8DE9770DDCFD9DB13CEE0907C6E0933F1C4D5C448601`
  - `mcpitanlib-fabric.1.21.1-3.7.2.jar` — `9B9099CBA56A6391489487AA9DDC427EBA31F921C9511814DD09264F9CD94813`
- 更新方式：从上游 release 取到新 jar 覆盖同名文件，同步改 `gradle.properties` 里的版本号与本文件的哈希。
