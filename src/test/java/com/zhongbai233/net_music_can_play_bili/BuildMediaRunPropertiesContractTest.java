package com.zhongbai233.net_music_can_play_bili;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建契约:client run 与媒体运行参数目录必须双向一致。
 *
 * <p>这是一个<b>文本级</b>契约(不解析 Gradle 模型),但已尽量堵住已知的绕过手法:</p>
 * <ul>
 *   <li>先剥离行注释与块注释 —— 注释掉转发不再能通过;</li>
 *   <li>断言 {@code jvmProp} 闭包非空且真的调用 {@code jvmArgument} —— 空闭包不再能通过;</li>
 *   <li>断言转发行位于 {@code client \{} 块之后,且全文不存在 {@code if (false)} —— 恒假分支不再能通过;</li>
 *   <li>反向断言目录里的每一个系统属性都真的被 main 源码读取 —— 死开关不再能藏在目录里。</li>
 * </ul>
 *
 * <p>真正的行为验证仍然是 {@code gradlew runClient}(转发是否生效由 JVM 系统属性决定)。</p>
 */
class BuildMediaRunPropertiesContractTest {
    /** 目录项必须三字段齐全(system / gradle / fallback)。 */
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\\[system: '([^']+)', gradle: '([^']+)', fallback: '[^']*'\\]");
    /** 目录条目数下限:整条被删时本测试必须失败。 */
    private static final int MIN_CATALOG_ENTRIES = 19;

    @Test
    void clientRunForwardsEverySharedMediaProperty() throws Exception {
        String build = stripComments(Files.readString(Path.of("build.gradle")));
        String normalized = build.replaceAll("\\s+", " ");

        // 1) jvmProp 必须是真正把属性传给 JVM 的闭包(空闭包会在此失败)。
        assertTrue(normalized.contains("def jvmProp = { key, value -> jvmArgument \"-D${key}=${value}\" }"),
                "jvmProp closure must actually call jvmArgument");

        // 2) 消费点必须逐条转发三个字段。
        String consumer = "sharedMediaRunProperties.each { spec -> jvmProp spec.system, prop(spec.gradle, spec.fallback) }";
        assertTrue(normalized.contains(consumer),
                "client run must forward system/gradle/fallback of every catalog entry");

        // 3) 消费点必须位于 client run 内(且不得被恒假条件包住)。
        int clientBlock = normalized.indexOf("client {");
        assertTrue(clientBlock >= 0 && clientBlock < normalized.indexOf(consumer),
                "the catalog consumer must live inside the client run block");
        assertFalse(normalized.contains("if (false)"),
                "a catalog consumer guarded by a constant-false branch is not a contract");

        // 4) 目录项必须三字段齐全,且不得缩水。
        List<String> systems = new ArrayList<>();
        Matcher matcher = CATALOG_ENTRY.matcher(normalized);
        while (matcher.find()) {
            systems.add(matcher.group(1));
        }
        assertTrue(systems.size() >= MIN_CATALOG_ENTRIES,
                "shared media run-property catalog shrank to " + systems.size());
        assertTrue(normalized.split("\\[system: '", -1).length - 1 == systems.size(),
                "every catalog entry must declare system/gradle/fallback");

        // 5) 反向契约:目录里的每个系统属性都必须真的被 main 源码读取(死开关会在此失败)。
        String mainSources = readAllMainSources();
        List<String> unread = new ArrayList<>();
        for (String system : systems) {
            if (!mainSources.contains(system)) {
                unread.add(system);
            }
        }
        assertTrue(unread.isEmpty(),
                "catalog entries nobody reads are dead switches: " + unread);
    }

    /** 剥离行注释与块注释:注释掉的代码不构成契约。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String readAllMainSources() throws IOException {
        Path root = Path.of("src", "main", "java");
        StringBuilder builder = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                builder.append(Files.readString(path));
            }
        }
        return builder.toString();
    }
}
