# 移植批次计划与进度(最终状态)

目标:NetMusicCanPlayBili 0.7.9-beta(NeoForge 26.1.2 / MC 26.1.2)→ NeoForge 21.1.250 / MC 1.21.1 完整保留功能。
项目:`F:\DeepSeek\bilibili\NetMusicCanPlayBili-1.21.1`

## 独立审计(无上下文子代理)+ 修复记录(2026-09-13)

审计结论:有条件可交付;发现的高危/次要项已全部处理:

- [x] **H2 假绿测试**:补 `tasks.test { useJUnitPlatform() }` → `gradle test --rerun-tasks` 真实执行 **941/941 通过(0 失败 0 错误 0 跳过)**(日志 test-run-6.txt)。
- [x] **H1 配方修复回归**:runServer(10:49)实测我方 11 个配方零错误,唯一剩余 error 为 NetMusic 本体 TLM 条件配方(未装 touhou_little_maid,上游行为,非本项目);runClient(11:00)无模型失败/无 FATAL、BiliAudioResolver 注册成功。
- [x] **强制全量重编译暴露的隐藏错误**:`MediaBindingCleanupService:161` Java 21 泛型推断(IdentityHashMap 菱形)修显式类型;`--rerun-tasks` 全绿。
- [x] **契约测试适配 1.21.1**(原 26.x 文本断言):BuildMediaRunPropertiesContractTest(恢复 22 条 sharedMediaRunProperties + collect + 双消费点 + modbench_version)、ControlConsoleModelResourceTest(items→models/item 布局)、VideoFlatLightingContractTest(NO_CARDINAL_LIGHTING→无 LightmapStateShard + createCompositeState(false))、VideoProjectorPresentationContractTest(getFloatOr 静态导入形态)、ControlConsoleTransformPersistenceContractTest(9 连读 float 的 3×3 布局)。
- [x] **M1/M2 语言键**:补 `gui.net_music_can_play_bili.pad.focus` 双语;en_us 补 3 个在用键(need_equipped_for_config、lyric/video_projector.item_linked)。
- [x] **M3 依赖范围**:mods.toml netmusic `[1.5.1,)` → `[1.5.2,)`。
- [x] **M6 弃用语法**:maven url space-assignment → `url = uri(...)`。
- [x] **refmap 文档漂移**:build.gradle 注释与 PORT-NOTES 修正(已 javap 实证 NeoForge 21.1 运行时为 Mojmap,无需 refmap)。
- [ ] M4(有意停用的 MinecartRevolution mixin 孤儿类)与 M5(孤儿贴图/模型)经复核为有意/被引用资源,不改(已在注释记录)。M7(服务器类引用 client.pad 纯逻辑类)为分包异味,无害不改。M8 环境残留进程已清理。

## 最终验收状态(2026-09-13)

- [x] `compileJava`:BUILD SUCCESSFUL(674 main 文件 + 42 SceneEditor 内联文件全绿)
- [x] `compileTestJava`:BUILD SUCCESSFUL(约 200 个 JUnit 测试全部编译通过)
- [x] `test`:BUILD SUCCESSFUL(exit 0)
- [x] `jar`:BUILD SUCCESSFUL → `build\libs\net_music_can_play_bili-0.7.9-beta+neo1.21.1.jar`(9.15 MB,含 native 二进制与全部资源)
- [x] `runClient`:BUILD SUCCESSFUL — 主菜单完成资源加载;`BiliAudioResolver registered with NetMusic`;`FFmpeg media native 解码器加载成功: 8.0.git`;无 FATAL / 无模型加载失败
- [x] `runServer`:BUILD SUCCESSFUL(exit 0)— 服务端注册完成、配方解析零错误

## 完成的主要工作

- 全部 6 类方块(ModernTurntable/LyricProjector/VideoProjector/Speaker/LiveStreamer/ControlConsole)与方块实体
- 物品:MP4、Pad、耳机(2 种)、全息眼镜、媒体管理工具
- 网络层 73 个 payload 文件 + 4 个 SavedData 的 1.21.1 化
- 渲染:投影仪/唱机/音箱/控制台 BER、YUV/NV12 三平面渲染类型(core shader 资产 + Sampler 绑定)、离屏 GUI(FBO 方案)、Pip 渲染 shim、地形 section 编译器(ModelBlockRenderer 真实剔除/AO)、手持屏渲染
- Mixin 19 个(1.21.1 目标签名逐一落实;删除 6 个 26.x 独有/死代码/第三方条目)
- B 站 API/媒体解码/同步/白名单/权限/Curios/穿戴 全链路保留
- 资源迁移:blockstates 前缀修复、26.x 模型任意角度旋转钳制为 1.21.1 合法角度、配方 26.x 字符串成分转换为 1.21.1 对象成分、item 模型补齐、按键分类本地化

## 已知未覆盖风险(记录)

1. 生产 refmap:Mixin AP 在 dev(mojmap)命名环境没有 named→SRG TSRG,开发编译用 `-Dmixin.env.disableRefMap=true`;dev 模式运行正常(已实测),生产(reobf)发布前需补映射链生成 refmap(docs/PORT-NOTES-1.21.1.md 备注)。
2. `rendertype_entity_translucent_emissive could not find sampler named Sampler2` 警告:自定义 YUV RenderType 在 shader 初始化时的良性警告;实际 YUV 显示失败会回退 RGBA/单平面且有日志(docs 说明)。
3. MinecartRevolution 第三方兼容 mixin 已停用(NeoForge 1.21.1 无该 mod 发行),待目标 mod 出现后按其 1.21.1 签名重新启用(源文件保留并有注释)。
4. 运行时完整游玩链路(播放真实 B 站视频/直播、Pad 地图、场景编辑器保存)需进游戏人工测试;自动校验覆盖到「客户端/服务端启动 + mod 注册 + native 加载 + 模型/配方零错误」。
5. VideoFrameUploader 的 direct-pointer 上传快路径在 1.21.1 无公开像素指针,已降级为逐像素写入(功能等价、性能略降,代码内注明)。
6. 读取过的临时开关 `-Dmixin.env.disableRefMap=true` 仅影响编译期 javac fork,不影响游戏运行。

## 交付清单

- 项目根:`F:\DeepSeek\bilibili\NetMusicCanPlayBili-1.21.1`
- 产物 jar:`build\libs\net_music_can_play_bili-0.7.9-beta+neo1.21.1.jar`
- 移植文档:`docs\PORT-NOTES-1.21.1.md`、`docs\RENDER-PORT-SPEC.md`、`docs\BATCH-PLAN.md`
- shim 层:`src\main\java\com\zhongbai233\net_music_can_play_bili\port\shim\({PortSubmitNodeCollector,PortWorldRenderEvents,PortDebugRenderTypes,PortPictureInPictureRenderer,PortGuiFramebufferBlitter,PortGuiTextures})`
- 编译/运行日志:`compile-err-1..21.txt`、`test-err-1/2.txt`、`test-run-1.txt`、`run-client-1..4.txt`、`run-server-1/2.txt`