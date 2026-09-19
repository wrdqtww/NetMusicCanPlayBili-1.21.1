package com.zhongbai233.net_music_can_play_bili;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建契约:client run 必须逐条转发媒体运行参数目录。
 *
 * <p>断言前会先剥离整行注释 —— 因此"把转发代码整段注释掉"无法骗过本测试
 * (上一版仅做文本包含判断,注释即可绕过);目录项必须是完整三元组,且目录不得缩水。</p>
 */
class BuildMediaRunPropertiesContractTest {
    /** 目录项必须三字段齐全(system / gradle / fallback)。 */
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\\[system: '[^']+', gradle: '[^']+', fallback: '[^']*'\\]");
    /** 本移植版目录的条目数下限:整条被删时本测试必须失败。 */
    private static final int MIN_CATALOG_ENTRIES = 21;

    @Test
    void clientRunForwardsEverySharedMediaProperty() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        String withoutLineComments = build.replaceAll("(?m)^\\s*//.*$", "");
        String normalized = withoutLineComments.replaceAll("\\s+", " ");

        // 1) 唯一消费点必须真正消费系统属性名、Gradle 属性名与回退值。
        assertTrue(normalized.contains(
                        "sharedMediaRunProperties.each { spec -> jvmProp spec.system, prop(spec.gradle, spec.fallback) }"),
                "client run must forward system/gradle/fallback of every catalog entry");

        // 2) 每条目录项都必须是完整三元组(缺 fallback 在此失败)。
        int completeEntries = countMatches(normalized, CATALOG_ENTRY);
        assertEquals(occurrences(normalized, "[system: '"), completeEntries,
                "every catalog entry must declare system/gradle/fallback");

        // 3) 目录不得缩水(整条被删在此失败)。
        assertTrue(completeEntries >= MIN_CATALOG_ENTRIES,
                "shared media run-property catalog shrank to " + completeEntries);

        // 4) 关键映射逐条存在。
        Map<String, String> criticalMappings = Map.of(
                "ncpb.video.real_bench.max_fps", "ncpbRealBenchMaxFps",
                "ncpb.live.real_bench", "ncpbRealLiveBench",
                "ncpb.live.real_bench.room", "ncpbLiveBenchRoom",
                "ncpb.video.native.av1_first_frame_probe_timeout_ms", "ncpbAv1FirstFrameProbeTimeoutMillis",
                "ncpb.video.native.av1_first_frame_probe_max_packets", "ncpbAv1FirstFrameProbeMaxPackets");

        criticalMappings.forEach((systemProperty, gradleProperty) -> assertTrue(normalized.contains(
                        "system: '" + systemProperty + "', gradle: '" + gradleProperty + "'"),
                () -> "missing shared run-property mapping for " + systemProperty));

        // 5) 消费点唯一:本移植版没有 Bench/paired client run,不应出现第二个转发点。
        assertEquals(1, occurrences(normalized, "sharedMediaRunProperties.each { spec ->"),
                "exactly one run consumes the catalog; a second one would be dead weight");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private static int countMatches(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
