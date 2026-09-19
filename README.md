# NetMusicCanPlayBili (NeoForge 1.21.1)

NetMusicCanPlayBili 的 **NeoForge 21.1.233 / Minecraft 1.21.1** 移植版。

给 NetMusic 增加 BiliBili 视频/直播音频播放支持：把 B 站视频（BV/av）写进音乐 CD 即可播放视频音频；
在广播喇叭输入 `live:直播间ID` 可播放直播音频。

上游主线（NeoForge 26.1.2 / Minecraft 26.1.2）：<https://github.com/zhongbai2333/NetMusicCanPlayBili>

## 版本矩阵

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge 编译基线 | 21.1.233 |
| NeoForge 运行范围 | 21.1.x（`neoforge.mods.toml` 声明 `[21.1,)`） |
| mod 版本 | 0.7.9-beta+neo1.21.1 |
| Java | 21 |
| Gradle | 8.14（wrapper 自带） |
| NetMusic | 1.5.2+（必需） |
| Curios | 9.4+（可选，穿戴设备） |

> 编译基线刻意对齐主流整合包所用的 21.1.233，而不是追最新版；
> 同一 1.21.1 版本线内 NeoForge API 向后兼容，因此运行范围写的是整个 21.1.x。

## 构建先决条件

- JDK 21（Gradle toolchain 会自动解析下载，也可用 `JAVA_HOME` 指定本机 JDK）。
- 首次构建需能访问 `maven.neoforged.net`、`api.modrinth.com/maven`、`services.gradle.org`。
- **不需要任何额外系统属性。** 若你所在网络有 TLS 中间人代理，请把证书配置写在自己的
  `~/.gradle/gradle.properties`，不要提交进本仓库。

## 构建

```bash
./gradlew build
```

产物：`build/libs/net_music_can_play_bili-0.7.9-beta+neo1.21.1.jar`

### 可选构建开关

| 属性 | 默认 | 作用 |
|---|---|---|
| `-PncpbMixinRefmap=true` | 关 | 让 mixin 注解处理器照常生成 refmap（完整映射环境下使用） |

## 移植文档

- [docs/PORT-NOTES-1.21.1.md](docs/PORT-NOTES-1.21.1.md)：26.x → 1.21.1 差异映射与工作方式
- [docs/RENDER-PORT-SPEC.md](docs/RENDER-PORT-SPEC.md)：渲染管线移植规格
- [docs/BATCH-PLAN.md](docs/BATCH-PLAN.md)：批次计划、验收状态与已知风险
- [docs/DISABLED-MIXINS.md](docs/DISABLED-MIXINS.md)：已停用 mixin 的源码归档与重新启用步骤
- [docs/REVIEW-2026-09-19.md](docs/REVIEW-2026-09-19.md)：对照外部反馈的审查记录

## 验收状态

`compileJava` / `compileTestJava` / `test` / `jar` 全部 BUILD SUCCESSFUL，单元测试全绿。

## 已知限制

- 生产 reobf 发布前需补 mixin refmap 映射链；当前默认关闭 refmap 生成，
  开发/测试环境运行正常（NeoForge 1.21.1 运行时即 Mojmap 命名，mixin 按字面名直接命中）。
- MinecartRevolution 第三方兼容 mixin 已停用（1.21.1 暂无该 mod 发行），源码见 docs/DISABLED-MIXINS.md。
- `VideoFrameUploader` 的 direct-pointer 上传快路径在 1.21.1 无公开像素指针，降级为逐像素写入（功能等价，性能略降）。
- 完整游玩链路（真实视频/直播播放、Pad 地图、场景编辑器保存）需进游戏人工验证。

## 许可

MIT，见 [LICENSE](LICENSE)。
