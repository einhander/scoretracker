package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Minimal Gradle distribution bootstrap used only by this generated scaffold.
 * It is intentionally small and is NOT the official Gradle wrapper JAR.
 * Run `gradle wrapper --gradle-version 8.5` to replace it with the official wrapper.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path root = jar.getParent().getParent().getParent().normalize();
        Path propsPath = root.resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) { props.load(in); }
        String urlText = props.getProperty("distributionUrl");
        if (urlText == null) throw new IllegalStateException("distributionUrl missing");
        URL url = new URL(urlText);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"services.gradle.org".equalsIgnoreCase(url.getHost())) {
            throw new SecurityException("Unexpected Gradle distribution host: " + url);
        }

        String fileName = new File(url.getPath()).getName();
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path cache = Paths.get(System.getProperty("user.home"), ".gradle", "wrapper", "temposcore-bootstrap");
        Files.createDirectories(cache);
        Path zip = cache.resolve(fileName);
        Path unpack = cache.resolve(baseName);
        Path gradleHome = unpack.resolve(baseName.replace("-bin", "").replace("-all", ""));

        if (!Files.exists(gradleHome.resolve("bin/gradle")) && !Files.exists(gradleHome.resolve("bin/gradle.bat"))) {
            if (!Files.exists(zip)) download(url, zip);
            if (Files.exists(unpack)) deleteTree(unpack);
            Files.createDirectories(unpack);
            unzip(zip, unpack);
            // Gradle zips unpack as gradle-X.Y, while baseName is gradle-X.Y-bin.
            if (!Files.exists(gradleHome)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(unpack, "gradle-*")) {
                    for (Path p : ds) { if (Files.isDirectory(p)) { gradleHome = p; break; } }
                }
            }
        }

        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path executable = gradleHome.resolve(windows ? "bin/gradle.bat" : "bin/gradle");
        if (!Files.exists(executable)) throw new FileNotFoundException("Gradle executable not found: " + executable);
        if (!windows) executable.toFile().setExecutable(true);

        List<String> command = new ArrayList<String>();
        if (windows) { command.add("cmd.exe"); command.add("/c"); }
        command.add(executable.toAbsolutePath().toString());
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        pb.inheritIO();
        int code = pb.start().waitFor();
        System.exit(code);
    }

    private static void download(URL url, Path target) throws IOException {
        System.err.println("Downloading " + url);
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        String targetPrefix = target.toFile().getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = target.resolve(e.getName());
                String canonical = out.toFile().getCanonicalPath();
                if (!canonical.startsWith(targetPrefix)) throw new IOException("Unsafe ZIP entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zin.read(buf)) >= 0) os.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path p : paths) Files.deleteIfExists(p);
    }
}
