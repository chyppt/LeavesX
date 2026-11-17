package org.leavesx.probe.neko;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 只读取 JVM 已公开的类信息与普通资源，不触发授权或 native 加载入口。 */
final class ClassOrigin {
    private static final int MAX_CLASS_BYTES = 2 * 1024 * 1024;

    private ClassOrigin() {}

    static void write(Class<?> type, Path root) throws IOException {
        ClassLoader loader = type.getClassLoader();
        String identity = Integer.toHexString(System.identityHashCode(loader));
        Path directory = root.resolve(identity);
        Files.createDirectories(directory);
        StringBuilder report = new StringBuilder("class=").append(type.getName()).append('\n');
        report.append("module=").append(type.getModule().getName()).append('\n');
        for (int i = 0; i < 8 && loader != null; i++, loader = loader.getParent()) {
            report.append("loader[").append(i).append("]=").append(loader.getClass().getName())
                .append('@').append(Integer.toHexString(System.identityHashCode(loader))).append('\n');
        }
        URL codeSourceLocation = null;
        try {
            var source = type.getProtectionDomain().getCodeSource();
            codeSourceLocation = source == null ? null : source.getLocation();
            report.append("codeSource=").append(safeUrl(codeSourceLocation)).append('\n');
        } catch (SecurityException error) {
            report.append("codeSource=访问被拒绝\n");
        }
        report.append("superclass=").append(type.getSuperclass() == null ? "null" : type.getSuperclass().getName()).append('\n');
        try {
            for (var method : type.getDeclaredMethods()) {
                report.append("method=").append(Modifier.toString(method.getModifiers())).append(' ')
                    .append(method.getName()).append('(');
                for (Class<?> parameter : method.getParameterTypes()) report.append(parameter.getName()).append(';');
                report.append("):").append(method.getReturnType().getName()).append('\n');
            }
        } catch (LinkageError | SecurityException error) {
            report.append("methodsUnavailable=").append(error.getClass().getName()).append('\n');
        }
        String resourceName = type.getName().replace('.', '/') + ".class";
        // 不调用未知 ClassLoader 的 getResource：云端类加载器可能会在这里联网下载资源。
        URL resource = localCodeSourceResource(codeSourceLocation, resourceName);
        report.append("resource=").append(safeUrl(resource)).append('\n');
        // 只从 CodeSource 指向的本地目录/JAR 读取，不跟随类加载器的资源查找逻辑。
        if (resource != null && localResource(resource)) {
            try (InputStream input = resource.openStream()) {
                byte[] bytes = input.readNBytes(MAX_CLASS_BYTES + 1);
                if (bytes.length > MAX_CLASS_BYTES) {
                    report.append("export=跳过，资源超过 2 MiB\n");
                } else if (!matches(type.getName(), bytes)) {
                    // 父加载器可能返回同名的另一份资源，不能把它当作运行中的实现。
                    report.append("export=跳过，资源内部类名不匹配或格式无效\n");
                } else {
                    Path output = directory.resolve("classes").resolve(resourceName);
                    Files.createDirectories(output.getParent());
                    Files.write(output, bytes);
                    report.append("resourceSha256=").append(sha256(bytes)).append('\n');
                    report.append("export=").append(output.toAbsolutePath()).append('\n');
                    report.append("exportNote=普通类资源，未经 JVM instrumentation 确认与当前定义字节一致\n");
                    // native 类可能引用未公开的云端参数类型，资源解析不需要解析那些类型。
                    for (var method : ClassFile.of().parse(bytes).methods()) {
                        report.append("resourceMethod=").append(Modifier.toString(method.flags().flagsMask())).append(' ')
                            .append(method.methodName().stringValue()).append(method.methodType().stringValue()).append('\n');
                    }
                }
            } catch (IOException | RuntimeException error) {
                report.append("exportError=").append(error.getClass().getName()).append('\n');
            }
        } else {
            report.append("export=无本地普通资源；未尝试提取受保护内存\n");
        }
        Files.writeString(directory.resolve(type.getName() + ".txt"), report, StandardCharsets.UTF_8);
    }

    static boolean matches(String className, byte[] bytes) {
        try {
            return ClassFile.of().parse(bytes).thisClass().asInternalName().equals(className.replace('.', '/'));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static boolean localResource(URL resource) {
        return resource.getProtocol().equals("file") || resource.getProtocol().equals("jrt")
            || resource.getProtocol().equals("jar") && resource.getFile().startsWith("file:");
    }

    static URL localCodeSourceResource(URL source, String resourceName) {
        if (source == null || !source.getProtocol().equals("file") || !source.getHost().isEmpty()) return null;
        try {
            Path path = Path.of(source.toURI()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                Path resource = path.resolve(resourceName).normalize();
                return resource.startsWith(path) && Files.isRegularFile(resource) ? resource.toUri().toURL() : null;
            }
            if (Files.isRegularFile(path)) {
                URI archive = path.toUri();
                return URI.create("jar:" + archive.toASCIIString() + "!/" + resourceName).toURL();
            }
        } catch (IOException | java.net.URISyntaxException | IllegalArgumentException error) {
            return null;
        }
        return null;
    }

    static String safeUrl(URL url) {
        if (url == null) return "null";
        return url.toExternalForm().split("[?#]", 2)[0].replaceAll("(?<=//)[^/@]+@", "");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 未提供 SHA-256", error);
        }
    }
}
