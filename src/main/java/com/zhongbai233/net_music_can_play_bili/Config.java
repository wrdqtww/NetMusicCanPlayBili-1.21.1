package com.zhongbai233.net_music_can_play_bili;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLE_LINK_WHITELIST = BUILDER
            .comment("是否启用服务端 BV/av 号与 NetMusic 第三方链接白名单")
            .translation("net_music_can_play_bili.config.enableLinkWhitelist")
            .define("enableLinkWhitelist", false);

    private static final ModConfigSpec.ConfigValue<String> LINK_WHITELIST_CONTACT_PLACEHOLDER = BUILDER
            .comment("玩家无权自行添加白名单时，在拒绝提示中显示的联系人名称")
            .translation("net_music_can_play_bili.config.linkWhitelistContactPlaceholder")
            .define("linkWhitelistContactPlaceholder", "OP4");

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableLinkWhitelist;
    public static String linkWhitelistContactPlaceholder;

    // 订阅基类 ModConfigEvent 才能同时覆盖 Loading 与 Reloading(热重载)。
    // 但 Unloading 也会进来,而那时 ModConfigSpec.acceptConfig(null) 已清空缓存,
    // ConfigValue.get() 会抛 "Cannot get config value before config is loaded.",故显式跳过。
    // 注意:必须保持单方法,否则订阅点(modEventBus.addListener(Config::onConfigChanged))只会绑定到其中一个子类。
    @SubscribeEvent
    static void onConfigChanged(final ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) {
            return;
        }
        loadValues();
    }

    private static void loadValues() {
        enableLinkWhitelist = ENABLE_LINK_WHITELIST.get();
        linkWhitelistContactPlaceholder = LINK_WHITELIST_CONTACT_PLACEHOLDER.get();
    }
}
