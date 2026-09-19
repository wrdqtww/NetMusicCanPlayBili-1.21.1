# NetMusicCanPlayBili → NeoForge 1.21.1 移植手册

源版本:NeoForge 26.1.2.76 / Minecraft 26.1.2(Java 25, Gradle 9.5, moddev 2.0.141)
目标版本:NeoForge 21.1.233 / Minecraft 1.21.1(Java 21, Gradle 8.14, moddev 1.0.24)
(编译基线对齐主流整合包使用的 21.1.233;运行期兼容范围见 `gradle.properties` 的 `neo_version_range`)

## 不变的(已验证,保真移植)

- NetMusic 本体 API(`com.github.tartaricacid.netmusic.*`):1.5.2 在 mc1.21.1 与 mc26.1 签名一致。
  - `MusicPlayResolverManager.registerResolver` 必须在 FMLLoadComplete 之前调用(mod 构造器内注册即可)。
- JNI 类名必须保持不变(二进制兼容 native DLL):
  - `com.zhongbai233.net_music_can_play_bili.media.codec.VideoJni`
  - `com.zhongbai233.net_music_can_play_bili.media.codec.Eac3Jni`
  - 两者 native 方法签名不得修改;`resources/native/**` 二进制原样分发。
- Scene Editor 源码:core 36 个文件零改动;minecraft 3 个文件仅依赖
  `Minecraft.getWindow().getGuiScaledWidth/Height()`(1.21.1 API 一致,无需改)。
- B 站 API 包(`bili/`)几乎纯 Java(ObjectMapper/Gson/HttpURLConnection),预计零改——
  除少量 NBT/Component 触点。
- 资源:blockstates/models/textures/lang/recipes/loot_tables、curios 数据、shaders 目录。
- 网络协议设计 (Play-side payloads, StreamCodec):1.21.1 同样支持。

## 必须处理的版本差异

1. 构建链
   - mods.toml:minecraft `[1.21.1,1.22)`、neoforge `[21.1,)`、netmusic `[1.5.2,)`。
   - mixin `compatibilityLevel` JAVA_25 → JAVA_21。
   - AT 中 26.1.2 的 `RenderType(String, RenderSetup)` 不存在 → 已移除(渲染处按需处理)。
   - moddev 2.x 的 `net.neoforged.moddev` DSL → moddev 1.x 语法。
   - NeoForge 21.1.233 + netmusicePPjVRpW(1.5.2)+ iris t3ruzodq(1.8.12)。

2. 注册
   - `DeferredRegister.createBlocks/createItems`(26.x)→ `DeferredRegister.create(Registries.*, MODID)`。
   - `new BlockEntityType<>(factory, Set.of(...))` → `BlockEntityType.Builder.of(factory, blocks...).build()`。

3. 渲染(最大移植面)
   - 26.x `net.minecraft.client.gui.GuiGraphicsExtractor` → 不存在;用 1.21.1 `GuiGraphics`。
   - 26.x `net.minecraft.client.renderer.SubmitNodeCollector` → 不存在;
     1.21.1 对应 `MultiBufferSource.BufferSource` / `RenderType` + `VertexConsumer`。
   - 26.x `ClientSubmittableGeometryEvent/SubmitCustomGeometryEvent` → 1.21.1
     `RenderLevelStageEvent` / `RenderHighlightEvent` 等。
   - 26.x `RenderTypes`(对象容器)→ 1.21.1 `RenderType.xxx()` 静态工厂。
   - 26.x `CameraRenderState` → 1.21.1 `Camera`。
   - 26.x `renderer.state.*` 状态对象 → 1.21.1 直传参数式。
   - DynamicTexture/ShaderInstance/RenderSystem APIs 逐个对照。

4. 输入/杂项
   - 26.x `net.minecraft.client.input.MouseButtonEvent`(事件对象)→ 1.21.1
     `MouseHandler`/`Screen.mouseClicked(double,double,int)` 参数式。
   - 26.x `world.level.storage.ValueInput/ValueOutput` → 1.21.1 `ByteBufCodecs`/`StreamCodec`。
   - Entity attachment/DataAttachments:1.21.1 用 transient fields 或 entity data。

5. GUI
   - Screen/AbstractContainerScreen 签名 26.x → 1.21.1 差异(init 与 render 参数)。
   - EditBox/Button/Tooltip:同名类但方法集不同(createNarrationMessage 等)。
   - GuiGraphics.fill/blit 差异按编译错误修复。

## 工作方式

- 贡献:第一轮全量复制源码 → `compileJava` → 按错误清单逐包修复
  (优先 net.minecraft.* import 差异,其次 method/signature)。
- 各包修复顺序:init/block/blockentity(类型注册) → network(数据流) →
  gui/menu → client/renderer → client 设备 → mixin(最后,需 1.21.1 目标签名)。
- mixin 目标类方法在 1.21.1 中若不存在 → 寻找等价注入点;纯 26.x 架构(mixin 目标类
  26.x 独有者)则改写为事件/回调方案。

## 快速参考:26.1.2 → 1.21.1 常见 import 映射

| 26.1.2 | 1.21.1 |
|---|---|
| net.minecraft.client.gui.GuiGraphicsExtractor | (无)改为 GuiGraphics 直接用法 |
| net.minecraft.client.renderer.SubmitNodeCollector | MultiBufferSource.BufferSource |
| net.minecraft.client.renderer.rendertype.RenderSetup | (无)RenderType.create(...) |
| net.minecraft.client.renderer.rendertype.RenderTypes | RenderType 类(静态工厂) |
| net.minecraft.client.renderer.state.level.CameraRenderState | net.minecraft.client.Camera |
| net.minecraft.world.level.storage.ValueInput/ValueOutput | ByteBufCodecs + StreamCodec |
| net.minecraft.client.input.MouseButtonEvent | MouseHandler / Screen 方法参数 |
| net.neoforged.neoforge.client.network.ClientPacketDistributor | PacketDistributor.sendTo... / ClientPacketListener |
| net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent | RenderLevelStageEvent(RegisterStageEvent 1.21.1) |

(表随编译错误动态补充)