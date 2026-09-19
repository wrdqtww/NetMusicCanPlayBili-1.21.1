# 移植批次计划与进度(最终状态)

目标:NetMusicCanPlayBili 0.7.9-beta(NeoForge 26.1.2 / MC 26.1.2)→ NeoForge 21.1.233 / MC 1.21.1 完整保留功能。
项目:本仓库根目录(即 `gradle.properties` / `build.gradle` 所在处)

## 第二轮修复(2026-09-19,对照整合包作者反馈)

反馈要点:①编译基线用了最新的 NeoForge 250,装不进 21.1.233 的整合包;②构建环境与本机耦合,他人难以构建;③存在 26.x 残留与无功效的僵尸快捷键。
完整证据与实测过程见 `docs/REVIEW-2026-09-19.md`。

- [x] **P0 编译基线降到 21.1.233**:实测 `compileJava` BUILD SUCCESSFUL,证明源码未使用 21.1.234~250 之间新增的 API;`neo_version_range` 保持 `[21.1,)` 以兼容更高的 21.1.x。
- [x] **P0 移除本机构建耦合**:删除 `gradle.properties` 的 `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT`(本机 TLS 中间人证书绕过,与 mod 功能无关);`-Dmixin.env.disableRefMap=true` 改为默认行为但可用 `-PncpbMixinRefmap=true` 关闭;文档里的 `F:\DeepSeek\...` 绝对路径改为仓库根描述。
- [x] **P1 僵尸快捷键接线**:`clear_equipped_bindings` 语言键与 `ClearEquippedBindingPacket` 长期没有任何发送方。现按「默认不绑定」注册键位,玩家可在按键设置里自行指派,链路闭合。
- [x] **P1 语言键对齐**:en_us 补齐 7 条缺失键(含唯一快捷键名与 6 条 message/tooltip);zh_cn 补齐 `config.enableDebugLog`。双语一度各 153 键对齐。后续第三/四轮复核发现其中 6 条 message/tooltip 与 `enableDebugLog` 本身都是零引用死键,已连同其余死键一并清除,最终收敛为 **120 键且完全对齐**。
- [x] **P2 清理 26.x 残余**:`build.gradle` 的 `if (false)` 死块先改成受 `-PenableModBench` 控制的开关;第三轮复审判定该开关体内为空循环(开不开关都不产生任何 run 配置)、属"把死代码包装成可用功能",遂连同该属性一并删除(见第三轮记录);孤儿 mixin `MinecartRevolutionProjectorLinkCleanupMixin` 移出源码树并归档到 `docs/DISABLED-MIXINS.md`(附重新启用步骤),jar 内不再包含该类。
- [x] **本轮验收**:`clean build` BUILD SUCCESSFUL;941/941 测试通过(0 失败 0 错误 0 跳过);jar 9.15 MB;native 6 平台齐全。

## 第三轮修复(2026-09-19,独立子代理复审后)

无上下文子代理对第二轮结果做了独立复审(逐 mixin 对照 MC 1.21.1 官方源码、javap 核对 NetMusic 类、FML 字节码核对配置事件),
确认硬骨架干净,同时指出 8 项实质缺陷,已全部处理:

- [x] **快捷键连发刷包(中等)**:GLFW 按住键会以约 30 次/秒产生 PRESS+REPEAT,而 `ClearEquippedBindingPacket` 是全项目唯一「无限频 + 可由键盘连发」的组合。现按同类包惯例加入 `NetworkRateLimiter.allow(..., 3/s)`。
- [x] **ModBench 空诺(中等)**:上一轮把 `if (false)` 换成 `-PenableModBench` 开关,但开关体内的循环依旧是空的 —— 开关打开不产生任何 run 配置,属把死代码包装成可用功能。现已**删除该空块、`sharedMediaPropertyNames`(全项目唯一引用处从未被使用)以及 `enableModBench`/`modbench_version` 两个随之失效的属性**。
- [x] **空洞契约测试(中等)**:`BuildMediaRunPropertiesContractTest` 只对 build.gradle 做文本计数,空循环也能通过。已重写:先剥离整行注释再做断言(注释掉转发不再能绕过)、目录项必须是完整 system/gradle/fallback 三元组、目录条目数不得低于 21、消费点唯一。**注意它仍是文本级断言,不解析 Gradle 模型**,真正的执行验证靠 `gradlew runClient`。
- [x] **僵尸配置项(中等)**:`enableDebugLog` 声明、加载齐全,但全项目零读取(项目已有 `NcpbSystemProperties`/`PadDiagnosticsProperties` 两套调试开关)。已删除该配置项与语言键;同时为剩下两个真实配置项显式指定翻译键并补齐中英译文。
- [x] **配置卸载抛异常(中等)**:`Config.onLoad(ModConfigEvent)` 订阅的是基类,Unloading 阶段也会进来,而此时 `ModConfigSpec` 缓存已被清空,`get()` 必抛 `IllegalStateException`。
  - 第三轮曾拆成 `Loading` / `Reloading` 两个方法,但主类只注册了 `Config::onLoad`,方法引用解析到 `ModConfigEvent.Loading`,**导致热重载失效(该轮引入的回归)**。
  - 第四轮改为单方法 `onConfigChanged(ModConfigEvent)` 订阅基类并显式跳过 `Unloading`,同时把注册点一并改名 —— 既覆盖 Loading/Reloading,又不触发卸载期异常。
- [x] **无人发送的网络包(中等)**:`MP4ContainerDeviceIdPacket` 已注册却无任何发送方(客户端只请求手持/快捷栏两类,容器槽位这条链从未接线),其客户端处理函数不可达。已删除该包、注册语句与不可达处理函数。
- [x] **零引用语言键(轻微)**:经逐键复核(并确认项目不存在字符串拼接式 `translatable` 调用),中英同步删除 33 条死键,第四轮又补删 1 条漏网键(`tooltip...headphones.mp4`)。双语从 153 键收敛为 **120 键且完全对齐**。
- [x] **死附魔标签(轻微)**:两个 `*_ENCHANTMENT_TAG` 常量零引用,配套的 `tags/enchantment/headphones.json` 是死数据(且缺少对应的 holographic_glasses 文件)。附魔的 `supported_items` 指向的是 `enchantable/*` item tag,与它无关。已删除常量、导入与数据目录。
- [x] **文档与实现不一致(轻微)**:修正「Mixin 19 个」(实际装配 17)、「73 个 payload」(network 包 72 个类,其中实现 CustomPacketPayload 的 41 个且全部注册)。


## 第四轮修复(2026-09-19,复审 8 项逐条验证后)

第二轮独立子代理对上轮修复做了逐项变异实验,结论:8 项中 5 项成立、3 项不彻底(其中配置热重载为本轮引入的回归)。已全部处理:

- [x] **配置热重载回归(中等)**:改回单方法订阅基类 + 显式跳过 `Unloading`(见上)。
- [x] **契约测试可被注释绕过(中等)**:断言前剥离整行注释,并新增三元组完整性、条目数下限两项检查。
- [x] **漏删死键(轻微)**:删除 `tooltip.net_music_can_play_bili.headphones.mp4`(同类键上轮已删,该条漏网)。
- [x] **协议版本未 bump(轻微)**:删除已注册 payload 属破坏性变更,`ModernTurntableNetwork` 的 registrar 版本 `6` → `7`。
- [x] **文档数字漂移(轻微)**:修正 payload 42→41、network 包 73→72,并消除本文档自相矛盾的表述。
- [x] **行尾污染(轻微)**:修复过程中曾误将 47 个未改内容的文件行尾统一,已按各自 HEAD 行尾精确还原;现全部改动文件的行尾与提交前一致。

- [x] **第四轮验收**:`clean build` BUILD SUCCESSFUL;941/941 测试通过;`runServer` / `runClient` 在 NeoForge 21.1.233 下无 mixin 错误、无 FATAL;jar 9.15 MB;native 6 平台;双语各 120 键。

## 独立审计(无上下文子代理)+ 修复记录(2026-09-13)

审计结论:有条件可交付;发现的高危/次要项已全部处理:

- [x] **H2 假绿测试**:补 `tasks.test { useJUnitPlatform() }` → `gradle test --rerun-tasks` 真实执行 **941/941 通过(0 失败 0 错误 0 跳过)**(日志 test-run-6.txt)。
- [x] **H1 配方修复回归**:runServer(10:49)实测我方 11 个配方零错误,唯一剩余 error 为 NetMusic 本体 TLM 条件配方(未装 touhou_little_maid,上游行为,非本项目);runClient(11:00)无模型失败/无 FATAL、BiliAudioResolver 注册成功。
- [x] **强制全量重编译暴露的隐藏错误**:`MediaBindingCleanupService:161` Java 21 泛型推断(IdentityHashMap 菱形)修显式类型;`--rerun-tasks` 全绿。
- [x] **契约测试适配 1.21.1**(原 26.x 文本断言):BuildMediaRunPropertiesContractTest(移植当时的形态:22 条 sharedMediaRunProperties + collect + 双消费点 + modbench_version;后续第二~四轮已收敛为 21 条、无 collect、单消费点、无 modbench_version,见下)、ControlConsoleModelResourceTest(items→models/item 布局)、VideoFlatLightingContractTest(NO_CARDINAL_LIGHTING→无 LightmapStateShard + createCompositeState(false))、VideoProjectorPresentationContractTest(getFloatOr 静态导入形态)、ControlConsoleTransformPersistenceContractTest(9 连读 float 的 3×3 布局)。
- [x] **M1/M2 语言键**:补 `gui.net_music_can_play_bili.pad.focus` 双语;en_us 补 3 个在用键(need_equipped_for_config、lyric/video_projector.item_linked)。
- [x] **M3 依赖范围**:mods.toml netmusic `[1.5.1,)` → `[1.5.2,)`。
- [x] **M6 弃用语法**:maven url space-assignment → `url = uri(...)`。
- [x] **refmap 文档漂移**:build.gradle 注释与 PORT-NOTES 修正(已 javap 实证 NeoForge 21.1 运行时为 Mojmap,无需 refmap)。
- [x] M4(停用的 MinecartRevolution mixin 孤儿类)已于第二轮修复移出源码树并归档,见下节与 `docs/DISABLED-MIXINS.md`。
- [ ] M5(孤儿贴图/模型)经逐文件引用核对**未发现孤儿**(item 模型走 1.21.1 约定式查找),不改。M7(服务器类引用 client.pad 纯逻辑类)为分包异味,无害不改。M8 环境残留进程已清理。

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
- 网络层 network 包 72 个类,其中实现 CustomPacketPayload 的 payload 41 个(全部注册) + 4 个 SavedData 的 1.21.1 化
- 渲染:投影仪/唱机/音箱/控制台 BER、YUV/NV12 三平面渲染类型(core shader 资产 + Sampler 绑定)、离屏 GUI(FBO 方案)、Pip 渲染 shim、地形 section 编译器(ModelBlockRenderer 真实剔除/AO)、手持屏渲染
- Mixin 装配 17 个(2 common + 15 client;1.21.1 目标签名逐一落实;相对 26.x 删除 5 个独有/死代码条目,另有 1 个第三方兼容条目移出源码树归档)
- B 站 API/媒体解码/同步/白名单/权限/Curios/穿戴 全链路保留
- 资源迁移:blockstates 前缀修复、26.x 模型任意角度旋转钳制为 1.21.1 合法角度、配方 26.x 字符串成分转换为 1.21.1 对象成分、item 模型补齐、按键分类本地化

## 已知未覆盖风险(记录)

1. 生产 refmap:Mixin AP 在 dev(mojmap)命名环境没有 named→SRG TSRG,开发编译用 `-Dmixin.env.disableRefMap=true`;dev 模式运行正常(已实测),生产(reobf)发布前需补映射链生成 refmap(docs/PORT-NOTES-1.21.1.md 备注)。
2. `rendertype_entity_translucent_emissive could not find sampler named Sampler2` 警告:自定义 YUV RenderType 在 shader 初始化时的良性警告;实际 YUV 显示失败会回退 RGBA/单平面且有日志(docs 说明)。
3. MinecartRevolution 第三方兼容 mixin 已停用(NeoForge 1.21.1 无该 mod 发行)。源码已移出 `src/` 归档到 `docs/DISABLED-MIXINS.md`,待目标 mod 出现后按其 1.21.1 签名重新启用(该文档内含完整步骤)。
4. 运行时完整游玩链路(播放真实 B 站视频/直播、Pad 地图、场景编辑器保存)需进游戏人工测试;自动校验覆盖到「客户端/服务端启动 + mod 注册 + native 加载 + 模型/配方零错误」。
5. VideoFrameUploader 的 direct-pointer 上传快路径在 1.21.1 无公开像素指针,已降级为逐像素写入(功能等价、性能略降,代码内注明)。
6. 读取过的临时开关 `-Dmixin.env.disableRefMap=true` 仅影响编译期 javac fork,不影响游戏运行。

## 交付清单

- 项目根:仓库根目录
- 产物 jar:`build\libs\net_music_can_play_bili-0.7.9-beta+neo1.21.1.jar`
- 移植文档:`docs\PORT-NOTES-1.21.1.md`、`docs\RENDER-PORT-SPEC.md`、`docs\BATCH-PLAN.md`
- shim 层:`src\main\java\com\zhongbai233\net_music_can_play_bili\port\shim\({PortSubmitNodeCollector,PortWorldRenderEvents,PortDebugRenderTypes,PortPictureInPictureRenderer,PortGuiFramebufferBlitter,PortGuiTextures})`
- 编译/运行日志:`compile-err-1..21.txt`、`test-err-1/2.txt`、`test-run-1.txt`、`run-client-1..4.txt`、`run-server-1/2.txt`