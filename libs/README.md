# libs

`maven.pitan76.net` 已被 Cloudflare 拦截（对 CI 与普通客户端一律返回 403 `Just a moment...`），GitHub Actions 无法再从该 maven 解析依赖，
因此把下面两个上游 jar 随仓库分发，构建改为 `files("libs/...")` 本地引用（见 `build.gradle`）。

| 文件 | 上游 | 版本 | 许可 |
|------|------|------|------|
| `itemalchemy-1.3.3.jar` | [Pitan76/item-alchemy](https://github.com/Pitan76/item-alchemy) | 1.3.3（1.19.2 多版本 jar） | MIT，见 `licenses/itemalchemy-LICENSE.txt` |
| `mcpitanlib-fabric-1.19.2-3.7.1.jar` | [Pitan76/MCPitanLib](https://github.com/Pitan76/MCPitanLib) | 3.7.1 | MIT，见 `licenses/mcpitanlib-LICENSE.txt` |

- 两个 jar **未做任何修改**；MIT 允许二进制再分发，版权与许可声明已随附于 `licenses/`。
- SHA-256：
  - `itemalchemy-1.3.3.jar` — `1575DCB53BDA3FB0C70891D50EA88893D526A739F5B3739681D2B58D10EC6142`
  - `mcpitanlib-fabric-1.19.2-3.7.1.jar` — `76D5E324A1EBA20959F871C1D6D5F28493B4DB03349A77202E932A7B048F2886`
- 更新方式：从上游 release 取到新 jar 覆盖同名文件，同步改 `gradle.properties` 里的版本号与本文件的哈希。
