# 渲染批次移植规格(Render port spec)

本文件为渲染层 1.21.1 移植提供统一技术决策。执行渲染批次的 agent 必须先读此文档。

## 权威参考

- 1.21.1 反编译源码:`build\moddev\artifacts\neoforge-<neo_version>-minecraft-sources.jar`(内含全部 net/minecraft deobf 源;当前基线 21.1.233)。
  - 用法:`jar tf` 列类,解压后用 read 读单个 .java。
- NetMusic 1.5.2(1.21.1)解压树:`port-ref\netmusic-x`(javap -classpath 查 ModelMusicPlayer$Block 等)。

## 总体策略

26.1.2 的 deferred render-state/GPU-buffer 架构(SubmitNodeCollector、SubmitNodeStorage、
GpuTexture、UberGpuBuffer、BlockStateModelSet、XxxRenderState、RenderSetup)在 1.21.1 不存在。
移植把"mixing deferred submit"降级为 1.21.1 的"立即提交 + BufferSource 批处理"模型,
功能语义(画什么、什么 z 序、什么 shader、视角/裁剪)一律保留。

## 1. 提交层 shim(新建,位于 src\main\java\com\zhongbai233\net_music_can_play_bili\port\shim\)

新建类:

1. `PortSubmitNodeCollector`:
   - 构造 `PortSubmitNodeCollector(MultiBufferSource.BufferSource source)`。
   - 方法 1:`void submitCustomGeometry(PoseStack poseStack, RenderType renderType, BiConsumer<PoseStack.Pose, VertexConsumer> emitter)`
     实现:`emitter.accept(poseStack.last(), source.getBuffer(renderType));`
     (RenderType 来自 net.minecraft.client.renderer.RenderType,1.21.1 静态工厂。)
   - 方法 2:`void submitText(PoseStack poseStack, float x, float y, Component text, boolean shadow, Font.DisplayMode mode, int color, int lightCoords, boolean depthShift)`
     实现:把 (text,pose 副本) 加入内部列表;提供 `void flushTexts(MultiBufferSource.BufferSource extra)`:
     对每条:用 Font.drawInBatch(...)[1.21.1 签名须按 jars 核实] 画。
   - (若源码中 submitText 签名不同,以调用点实际形态为准,保持参数全保留。)
2. `PortRenderGeometryEvent`(替代 SubmitCustomGeometryEvent 角色):不再有该事件,
   改为各订阅点自己创建 `PortSubmitNodeCollector`。原"event"对象一拆为三:
   `PoseStack`, `PortSubmitNodeCollector`, `Camera`(由 RenderLevelStageEvent 提供)。
   建议新建 helper:`PortWorldRenderEvents.begin(RenderLevelStageEvent evt)` 返回
   `PortSubmitNodeCollector`(内部 new MultiBufferSource.BufferSource(Tesselator.getInstance().getBuilder(), 4 << 10)),
   并提供 `end(collector)` → text flush + `source.endBatch()`。
   注意:若用 Minecraft.renderBuffers().bufferSource(),不要 endBatch(共享);如果用自建,
   记住每一阶段最后 endBatch。

## 2. 事件替换表

| 26.1.2 | 1.21.1 |
|---|---|
| SubmitCustomGeometryEvent(event.getSubmitNodeCollector(), event.getPoseStack()?) | RenderLevelStageEvent(选择 stage AFTER_TRANSLUCENT_BLOCKS 或 AFTER_PARTICLES 按内容;至少保留一个) |
| event.getSubmitNodeCollector() | PortWorldRenderEvents.begin(evt) |
| collector=null 防御 | 保留同防御 |
| net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent import | net.neoforged.neoforge.client.event.RenderLevelStageEvent |

原有类的所有 public static 方法若以 SubmitCustomGeometryEvent 为参数,改为
`(RenderLevelStageEvent event)` 并从 event.getPoseStack() 取 pose、由 event 构造 collector。

## 3. 模型提交

`collector.submitModel(model, state, poseStack, TextureLocation, light, overlay, hue, breakProgress)`
→ 1.21.1:
```
VertexConsumer vc = source.getBuffer(RenderType.entityCutout(ModelMusicPlayer.TEXTURE));
model.setupAnim(...time base...); // 1.21.1 NetMusic model 方法按 javap 查
model.renderToBuffer(poseStack, vc, light, overlay);   // 参数 rgb 等按 javap
```
NetMusicDiscModelAdapter 的 state 字段(facing/hasDisc/discRotation)直接映射到模型
属性(ModelMusicPlayer.Block 1.21.1 的字段/方法,用 `javap -p -classpath port-ref\netmusic-x
com.github.tartaricacid.netmusic.client.model.ModelMusicPlayer$Block` 核对,字段可能是
public int/boolean 或通过方法)。

## 4. Gpu 纹理/上传 → 1.21.1

- GpuTexture/UberGpuBuffer/ProjectionMatrixBuffer/SubmitNodeStorage → 删类或重写为
  1.21.1 等价(以使用点为准):
  - 双平面 YUV:1.21.1 用 3 个 `DynamicTexture`(NativeImage)上传 Y/U/V,
    或单 NativeImage 上传 + shader;RenderSystem 相关调用:
    `RenderSystem.setShaderTexture(int sampler?, ResourceLocation)` 1.21.1 签名核对 jar。
  - RGBA:保持 DynamicTexture + glTexSubImage2D 即可(1.21.1 支持)。
- ProjectionMatrixBuffer/ShaderSource/GlRenderPipeline/GlPipelineProperties 等 26.x
  核心类:按使用点找到 1.21.1 语义替代:
  - RenderSystem.getProjectionMatrix() / getModelViewMatrix()(1.21.1 用 RenderSystem)
  - ShaderProgram(1.21.1 com.mojang.blaze3d.shaders.Program + GameRenderer.getShaderProgram? 按 jar)。

## 5. RenderType 命名映射

`net.minecraft.client.renderer.rendertype.RenderType` 的 26.x 对象容器在 1.21.1 为
`net.minecraft.client.renderer.RenderType` 静态工厂。已实测 1.21.1 缺以下 26.x 工厂,
按右列替换(全部从 1.21.1 RenderType 源核对,源文件在 port-ref\mc-src):

| 26.x | 1.21.1 |
|---|---|
| RenderType.itemCutout(id) | RenderType.entityCutout(id) |
| RenderType.itemTranslucent(id) | RenderType.entityTranslucent(id) |
| RenderType.linesTranslucent() | RenderType.lines() |
| RenderTypes.xxx（对象容器写法） | RenderType.xxx |
| 26.x 自定义视频/YUV RenderType(RenderSetup 构造) | RenderType.create(name, format, mode, bufSize, false, sortOnUpload, setup, clear) 1.21.1 签名按 jar 核对后构建 |

1.21.1 RenderType 现成工厂(供选择):solid/cutout/cutoutMipped/translucent、
entitySolid/entityCutout/entityCutoutNoCull/entityTranslucent/entityTranslucentEmissive/
beaconBeam/entitySmoothCutout/text/textSeeThrough/lines/lineStrip/debugLineStrip/
debugFilledBox/debugQuads/gui/guiOverlay/crumbling 等。

## 6. Iris/shaderpack 兼容

`IrisShaderpackCompat` 依赖的 Iris 1.21.1(1.8.x)API 与 26.x 差异:
- `IrisApi`/`IrisApiV2` 在 1.8.x 提供 getInstance().isShaderPackActive();
- 1.21.1 中 sampler/shader 绑定走 RenderSystem.setShaderTexture 与
  `net.irisshaders.iris.api.v0`?凡不确定:降级为"检测 pack active → 关闭 YUV shader 自定义路径,
  走 RGBA 回退",但保留检测 API(用反射调用 iris api,避免硬链接版本):
  反射 `net.irisshaders.iris.api.v0.IrisApi`, styles:isShaderPackActive()。
  compileOnly iris 依赖 t3ruzodq 保留在 build.gradle。
- 所有 Iris 强类型 import(哪句编译不过再逐个反射化)。

## 7. GUI/render state

- `net.minecraft.client.gui.render.*`、`renderer.state.gui.*`、`TooltipDisplay` → 1.21.1
  的 Tooltip/GuiGraphics/Screen 匹配(按 jar)。屏幕内 debug 绘制类迁移到
  `RenderGuiOverlayEvent.Post` 或 ForgeGuiLayer 等(以调用点为准)。
- GuiRenderState → 无;直接 GuiGraphics。

## 8. 禁止事项

- 不得删除功能:任何"无法移植"的渲染路径必须用 1.21.1 等价实现(functionality 保持)。
- 不做风格重构,只做 API 移植。
- 每次仅修改指定给本 agent 的文件,不:

改写 build.gradle、不注册新 mixin 配置(除非单列任务要求)。

## 校验

- 每个文件改完 grep 确认不再有 26.x 包 import(net.minecraft.client.renderer.state、
  net.minecraft.client.gui.render、com.mojang.blaze3d.textures、buffers、opengl.Gl*、
  neoforge.client.event.SubmitCustomGeometryEvent、rendertype.RenderType 等)。
- 依赖 compileJava(主线程统一跑)。