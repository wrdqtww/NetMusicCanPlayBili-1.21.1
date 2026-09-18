# NetMusicCanPlayBili (NeoForge 1.21.1)

NetMusicCanPlayBili 的 **NeoForge 21.1.250 / Minecraft 1.21.1** 移植版。

给 NetMusic 增加 BiliBili 视频/直播音频播放支持：把 B 站视频（BV/av）写进音乐 CD 即可播放视频音频；
在广播喇叭输入 `live:直播间ID` 可播放直播音频。

上游主线（NeoForge 26.1.2 / Minecraft 26.1.2）：<https://github.com/zhongbai2333/NetMusicCanPlayBili>

## 版本矩阵

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 |
| mod 版本 | 0.7.9-beta+neo1.21.1 |
| Java | 21 |
| Gradle | 8.14（wrapper 自带） |

## 构建

```bash
./gradlew build
```

产物：`build/libs/net_music_can_play_bili-0.7.9-beta+neo1.21.1.jar`

## 移植文档

- [docs/PORT-NOTES-1.21.1.md](docs/PORT-NOTES-1.21.1.md)：26.x → 1.21.1 差异映射与工作方式
- [docs/RENDER-PORT-SPEC.md](docs/RENDER-PORT-SPEC.md)：渲染管线移植规格
- [docs/BATCH-PLAN.md](docs/BATCH-PLAN.md)：批次计划、验收状态与已知风险

## 验收状态

`compileJava` / `compileTestJava` / `test` / `jar` / `runClient` / `runServer` 全部 BUILD SUCCESSFUL；
单元测试 941/941 通过（0 失败 0 错误 0 跳过）。

## 已知限制

- 生产 reobf 发布前需补 mixin refmap 映射链；开发/测试环境使用 `-Dmixin.env.disableRefMap=true`，运行正常。
- MinecartRevolution 第三方兼容 mixin 已停用（1.21.1 暂无该 mod 发行）。
- `VideoFrameUploader` 的 direct-pointer 上传快路径在 1.21.1 无公开像素指针，降级为逐像素写入（功能等价，性能略降）。
- 完整游玩链路（真实视频/直播播放、Pad 地图、场景编辑器保存）需进游戏人工验证。

## 许可

MIT，见 [LICENSE](LICENSE)。
