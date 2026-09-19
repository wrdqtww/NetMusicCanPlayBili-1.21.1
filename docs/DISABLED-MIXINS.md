# 已停用的 Mixin（源码树外归档）

本文件保存**当前不装配、但将来可能重新启用**的 mixin 源码。它们不在 `src/` 下，
因此不会编译进发布 jar，也不会出现在 `net_music_can_play_bili.mixins.json` 中。

迁移原因：这类类如果留在 `mixin/` 包里，会被误读为「26.x 移植残留」；
但它们承载的是对第三方 mod 的兼容逻辑，删除会丢失信息，故归档在此。

---

## 1. MinecartRevolutionProjectorLinkCleanupMixin

**目标 mod**：MinecartRevolution（`ml.mypals.minecartrevolution`）
**停用原因**：该第三方 mod 在 NeoForge 1.21.1 无发行版，目标类
`ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler` 缺失，
若装配会导致服务端 mixin 加载崩溃。
**已从** `net_music_can_play_bili.mixins.json` **的 `mixins` 列表移除**。

**重新启用步骤**（目标 mod 发布 1.21.1 版本后）：

1. 用 `javap` 核对新版本的 `MinecartInteractionEventHandler#interact` 方法签名；
2. 按新签名修正下方 `@Inject` 的 `method` 与参数列表；
3. 校验注入点 `Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V` 是否仍存在；
4. 将文件放回 `src/main/java/com/zhongbai233/net_music_can_play_bili/mixin/`；
5. 把类名加回 `net_music_can_play_bili.mixins.json` 的 `mixins` 数组；
6. 运行 `gradlew runServer` 确认无 mixin 装配错误。

```java
package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecartRevolution 成功装载投影仪后，清除玩家手中剩余物品的临时唱片机链接。
 *
 * <p><b>1.21.1 停用说明:</b>第三方 mod MinecartRevolution 在 NeoForge 1.21.1 无发行版,
 * 目标类 ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler 缺失会导致服务端
 * mixin 加载崩溃。本兼容注入已从 net_music_can_play_bili.mixins.json 移除(不再装配),源文件保留,
 * 待目标 mod 出现 NeoForge 1.21.1 发行版后,按其新方法签名重新启用(校验 interact 签名与注入点后,
 * 将该类重新加入 mixins.json 的 "mixins" 列表)。</p>
 */
@Pseudo
@Mixin(targets = "ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler", remap = false)
public abstract class MinecartRevolutionProjectorLinkCleanupMixin {
    @Inject(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V", shift = At.Shift.AFTER), require = 1)
    private static void net_music_can_play_bili$clearConsumedProjectorLink(Player player, InteractionHand hand,
            AbstractMinecart interacted, Level level, CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        ItemStack remainder = player.getItemInHand(hand);
        if (remainder.isEmpty() || !remainder.is(ModItems.VIDEO_PROJECTOR.get())) {
            return;
        }
        LinkHelper.clearLinkFromItem(remainder);
        VideoProjectorBlock.clearLinkedBlockEntityData(remainder);
    }
}
```
