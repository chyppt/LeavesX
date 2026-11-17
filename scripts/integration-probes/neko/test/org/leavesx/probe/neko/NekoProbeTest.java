package org.leavesx.probe.neko;

import cn.dg32z.neko.NekoAntiCheat;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NekoProbeTest {
    @TempDir Path temporary;

    @Test
    void inspectsRealNativeEntryWithoutInitializingIt() throws Exception {
        ClassOrigin.write(NekoAntiCheat.class, temporary);
        String report = report(NekoAntiCheat.class, temporary);
        assertTrue(report.contains("class=cn.dg32z.neko.NekoAntiCheat"));
        assertTrue(report.contains("native onEnable()"));
        assertTrue(report.contains("resourceSha256="));
        // 执行静态初始化会进入 JNICLoader；该测试不需要也不提供 native 调用环境。
        assertTrue(report.contains("exportNote="));
    }

    @Test
    void validatesInternalClassNameBeforeExportingAResource() throws Exception {
        byte[] bytes = fixtureBytes();
        assertTrue(ClassOrigin.matches(OriginFixture.class.getName(), bytes));
        assertFalse(ClassOrigin.matches("example.MovementValidation", bytes));
        assertFalse(ClassOrigin.matches(OriginFixture.class.getName(), new byte[0]));
        assertFalse(ClassOrigin.matches(OriginFixture.class.getName(), new byte[]{1, 2, 3, 4}));
    }

    @Test
    void distinguishesDynamicClassWithoutResources() throws Exception {
        Class<?> type = new FixtureLoader(null).define();
        ClassOrigin.write(type, temporary);
        String report = report(type, temporary);
        assertTrue(report.contains("resource=null"));
        assertTrue(report.contains("未尝试提取受保护内存"));
        assertFalse(report.contains("resourceSha256="));
    }

    @Test
    void rejectsResourceWithWrongClassIdentity() throws Exception {
        Path sourceRoot = temporary.resolve("wrong-source");
        Path resource = sourceRoot.resolve(OriginFixture.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(resource.getParent());
        try (var input = NekoProbeTest.class.getResourceAsStream("NekoProbeTest.class")) {
            Files.write(resource, input.readAllBytes());
        }
        Class<?> type = new FixtureLoader(sourceRoot.toUri().toURL()).define();
        ClassOrigin.write(type, temporary);
        String report = report(type, temporary);
        assertTrue(report.contains("资源内部类名不匹配"));
        assertFalse(report.contains("resourceSha256="));
    }

    @Test
    void neverOpensRemoteClassResourcesAndOmitsUrlSecrets() throws Exception {
        URL remote = java.net.URI.create("https://user:password@127.0.0.1:1/check.class?token=shouldNotBeExportedToken#secret").toURL();
        Class<?> type = new FixtureLoader(remote).define();
        ClassOrigin.write(type, temporary);
        String report = report(type, temporary);
        assertTrue(report.contains("https://127.0.0.1:1/check.class"));
        assertFalse(report.contains("password"));
        assertFalse(report.contains("shouldNotBeExportedToken"));
        assertFalse(report.contains("#secret"));
        assertFalse(report.contains("exportError="));
    }

    @Test
    void acceptsOnlyLocalFileJarOrJrtResources() throws Exception {
        assertTrue(ClassOrigin.localResource(java.net.URI.create("file:/tmp/M.class").toURL()));
        assertTrue(ClassOrigin.localResource(java.net.URI.create("jar:file:/tmp/a.jar!/M.class").toURL()));
        assertFalse(ClassOrigin.localResource(java.net.URI.create("jar:https://example.com/a.jar!/M.class").toURL()));
        assertFalse(ClassOrigin.localResource(java.net.URI.create("https://example.com/M.class").toURL()));
        assertNull(ClassOrigin.localCodeSourceResource(java.net.URI.create("file://example.com/shared/").toURL(), "M.class"));
    }

    @Test
    void wireCountsIncludeWrappedHistoryButStopAtTheRecordingBoundary() throws Exception {
        Path file = temporary.resolve("wire-counts.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, file);
        try (trace) {
            for (int i = 0; i < MovementTrace.BEFORE_LINES + 20; i++) trace.appendWire(true, "wire-out");
            trace.appendWire(false, "wire-in");
            trace.append("first-offset", true);
        }
        trace.appendWire(true, "late-wire");
        assertTrue(trace.awaitClosed(5000));
        assertEquals("wire-out=" + (MovementTrace.BEFORE_LINES + 20) + " wire-in=1", trace.wireCounts());
        assertTrue(Files.readString(file).contains(trace.wireCounts()));
        assertFalse(Files.readString(file).contains("late-wire"));
    }

    @Test
    void keepsObserverMetadataWhenTheEventHistoryWraps() throws Exception {
        Path file = temporary.resolve("metadata.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, file,
            "probe-version=1.1.1 wire-observer=true server=26.1.2\nmetadata");
        try (trace) {
            for (int i = 0; i < MovementTrace.BEFORE_LINES + 30; i++) trace.append("before-" + i, false);
            trace.append("first-offset", true);
        }
        assertTrue(trace.awaitClosed(5000));
        String text = Files.readString(file);
        assertTrue(text.contains("# probe-version=1.1.1 wire-observer=true server=26.1.2 metadata"));
        assertTrue(text.indexOf("wire-observer=true") < text.indexOf("# first-anomaly"));
        assertFalse(text.contains("\nmetadata"));
    }

    @Test
    void retainsFirstAnomalyWithBoundedHistoryAndFollowingEvents() throws Exception {
        Path file = temporary.resolve("anomaly.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, file);
        try (trace) {
            for (int i = 0; i < MovementTrace.BEFORE_LINES + 20; i++) trace.append("before-" + i + " ", false);
            trace.append("first-offset", true);
            for (int i = 0; i < MovementTrace.AFTER_LINES + 20; i++) trace.append("after-" + i + " ", false);
            assertTrue(trace.triggered());
            assertFalse(trace.accepting());
        }
        assertTrue(trace.awaitClosed(5000));
        String text = Files.readString(file);
        assertTrue(text.contains("before-20 "));
        assertFalse(text.contains("before-19 "));
        assertTrue(text.contains("first-offset"));
        assertTrue(text.contains("after-" + (MovementTrace.AFTER_LINES - 2) + " "));
        assertFalse(text.contains("after-" + (MovementTrace.AFTER_LINES - 1) + " "));
        assertTrue(text.contains("# closed="));
    }

    @Test
    void recordsQuietWindowAndMakesCloseIdempotent() throws Exception {
        Path file = temporary.resolve("quiet.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, file);
        for (int i = 0; i < MovementTrace.BEFORE_LINES + 40; i++) trace.append("position-" + i + " ", false);
        trace.close();
        trace.close();
        trace.append("late-record", true);
        assertTrue(trace.awaitClosed(5000));
        String text = Files.readString(file);
        assertFalse(text.contains("position-39 "));
        assertTrue(text.contains("position-40 "));
        assertTrue(text.contains("position-" + (MovementTrace.BEFORE_LINES + 39) + " "));
        assertFalse(text.contains("late-record"));
        assertNull(trace.failure());
    }

    @Test
    void expiresBeforeAcceptingFurtherEvents() throws Exception {
        Path file = temporary.resolve("expired.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 0, file);
        try (trace) {
            trace.append("expired-event", true);
        }
        assertTrue(trace.awaitClosed(5000));
        assertFalse(Files.readString(file).contains("expired-event"));
    }

    @Test
    void concurrentEventProducersPreserveTheCaptureLimit() throws Exception {
        Path file = temporary.resolve("concurrent.log");
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, file);
        try (trace) {
            try (var producers = Executors.newFixedThreadPool(4)) {
                for (int p = 0; p < 4; p++) producers.submit(() -> {
                    for (int i = 0; i < 500; i++) trace.append("event", true);
                });
                producers.shutdown();
                assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertTrue(trace.awaitClosed(5000));
        assertEquals(MovementTrace.AFTER_LINES, Files.readAllLines(file).stream().filter(line -> line.endsWith(" event")).count());
    }

    private static String report(Class<?> type, Path root) throws IOException {
        String id = Integer.toHexString(System.identityHashCode(type.getClassLoader()));
        return Files.readString(root.resolve(id).resolve(type.getName() + ".txt"));
    }

    private static byte[] fixtureBytes() throws IOException {
        try (var input = OriginFixture.class.getResourceAsStream("OriginFixture.class")) {
            return input.readAllBytes();
        }
    }

    private static final class FixtureLoader extends SecureClassLoader {
        private final URL source;
        FixtureLoader(URL source) { this.source = source; }
        Class<?> define() throws IOException {
            byte[] bytes = fixtureBytes();
            return defineClass(OriginFixture.class.getName(), bytes, 0, bytes.length, new CodeSource(source, (Certificate[]) null));
        }
        @Override public URL getResource(String name) { throw new AssertionError("不得触发未知类加载器资源查找"); }
    }
}

final class OriginFixture {
    static { throwIfInitialized(); }
    private static void throwIfInitialized() { throw new AssertionError("诊断不应该初始化目标类"); }
    native void check();
}
