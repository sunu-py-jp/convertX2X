/*
 * Copyright (c) 2026 convertX2X contributors
 * SPDX-License-Identifier: MIT
 */
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * JDK-only license packaging. Keep identical copies in the independent Java modules.
 * No license text is downloaded at build time. Changes to dependencies require review.
 */
public class PackageLicenses {
    record Dependency(String sha256, List<String> notices) {}
    private final Path module;
    private final Path javaNotices;
    private final SortedMap<String, Dependency> inventory = new TreeMap<>();
    private final SortedMap<String, Path> runtime = new TreeMap<>();
    private final SortedMap<String, byte[]> bundle = new TreeMap<>();

    PackageLicenses(Path module, Path classpath) throws Exception {
        this.module = module.toAbsolutePath().normalize();
        javaNotices = this.module.resolve("third-party/java");
        verifyChecksums(javaNotices, true);
        for (String line : Files.readAllLines(javaNotices.resolve("dependencies.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            require(fields.length == 3 && fields[0].endsWith(".jar")
                    && fields[1].matches("[a-f0-9]{64}") && !fields[2].isBlank(),
                    "Invalid dependencies.tsv row");
            var dependency = new Dependency(fields[1], List.of(fields[2].split(",")));
            require(inventory.putIfAbsent(fields[0], dependency) == null,
                    "Duplicate dependency: " + fields[0]);
            for (String notice : dependency.notices()) readRequired(safeResolve(javaNotices, notice));
        }
        require(!inventory.isEmpty(), "Empty dependency inventory");
        for (String entry : Files.readString(classpath).strip().split(Pattern.quote(File.pathSeparator))) {
            require(!entry.isBlank(), "Empty runtime classpath");
            Path jar = Path.of(entry);
            String name = jar.getFileName().toString();
            require(runtime.putIfAbsent(name, jar) == null, "Duplicate runtime filename: " + name);
            verifyDependency(jar);
        }
        require(runtime.keySet().equals(inventory.keySet()),
                "Dependency inventory differs from resolved runtime. Update and review third-party/java.");
        add("LICENSE", readRequired(this.module.resolve("LICENSE")));
        add("NOTICE.md", readRequired(this.module.resolve("NOTICE.md")));
        addTree(javaNotices, "licenses/java/", false);
        Path wrapper = this.module.resolve("third-party/maven-wrapper");
        readRequired(wrapper.resolve("LICENSE"));
        readRequired(wrapper.resolve("NOTICE"));
        verifyChecksums(wrapper, true);
        addTree(wrapper, "licenses/maven-wrapper/", false);
        Path fonts = this.module.resolve("src/main/resources/fonts");
        require(Files.isDirectory(fonts), "Bundled font directory is missing");
        try (var dirs = Files.list(fonts)) {
            for (Path dir : dirs.toList()) {
                require(Files.isDirectory(dir), "Place fonts and their licenses in a checksummed family directory: " + dir);
                verifyChecksums(dir, false);
                try (var files = Files.list(dir)) {
                    require(files.anyMatch(p -> p.getFileName().toString().matches("(?i)(LICENSE.*|OFL.*)")),
                            "Missing font license: " + dir.getFileName());
                }
            }
        }
        addTree(fonts, "licenses/fonts/", true);
        var catalog = new StringBuilder("convertX2X — Third-party licenses and notices\n\n")
                .append("Application code: MIT (LICENSE). Third-party works keep their own licenses.\n")
                .append("Unmodified upstream text follows. See licenses/java/SOURCE.md for provenance.\n")
                .append("JNA offers alternative licenses; this distribution uses its Apache-2.0 option.\n\n")
                .append("Resolved runtime dependencies (including the Functions host API):\n");
        for (var entry : inventory.entrySet()) {
            catalog.append(entry.getKey()).append("\n");
            for (String notice : entry.getValue().notices()) {
                catalog.append("  licenses/java/").append(notice).append("\n");
            }
        }
        // Include all retained texts, including nested native-library notices.
        for (var entry : bundle.entrySet()) {
            if (!entry.getKey().startsWith("licenses/") || isManifest(entry.getKey())) continue;
            catalog.append("\n\n===== ").append(entry.getKey()).append(" =====\n\n")
                    .append(new String(entry.getValue(), StandardCharsets.UTF_8)).append("\n");
        }
        add("THIRD-PARTY-NOTICES.txt", catalog.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 4 || args.length == 5,
                "Usage: java scripts/PackageLicenses.java prepare MODULE CLASSPATH CLASSES\n"
                + "   or: java scripts/PackageLicenses.java stage|check MODULE CLASSPATH STAGE APP_JAR");
        var packager = new PackageLicenses(Path.of(args[1]), Path.of(args[2]));
        Path destination = Path.of(args[3]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "prepare" -> {
                require(args.length == 4, "prepare takes four arguments");
                packager.writeBundle(destination.resolve("META-INF"));
            }
            case "stage", "check" -> {
                require(args.length == 5, "stage/check takes five arguments");
                packager.verifyStageJars(destination, args[4]);
                if (args[0].equals("stage")) packager.writeBundle(destination);
                packager.verifyBundle(destination);
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
        System.out.println("License verification passed: " + packager.inventory.size()
                + " runtime dependencies (" + args[0] + ").");
    }

    private void verifyStageJars(Path stage, String appJar) throws Exception {
        Path application = safeResolve(stage, appJar);
        require(Files.isRegularFile(application), "Application JAR is missing");
        Set<String> shipped = new TreeSet<>();
        try (var files = Files.walk(stage)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                if (file.equals(application)) continue;
                verifyDependency(file);
                require(shipped.add(file.getFileName().toString()), "Duplicate packaged JAR: " + file);
            }
        }
        Set<String> expected = new TreeSet<>(runtime.keySet());
        // azure-functions-maven-plugin excludes the API supplied by the Functions host.
        expected.removeIf(name -> name.startsWith("azure-functions-java-core-library-"));
        require(shipped.equals(expected), "Packaged JAR set differs from the resolved runtime dependencies");
        try (var jar = new ZipFile(application.toFile())) {
            for (var entry : bundle.entrySet()) {
                var zipEntry = jar.getEntry("META-INF/" + entry.getKey());
                require(zipEntry != null, "Missing notice in application JAR: " + entry.getKey());
                try (var in = jar.getInputStream(zipEntry)) {
                    require(Arrays.equals(in.readAllBytes(), entry.getValue()),
                            "Outdated notice in application JAR: " + entry.getKey());
                }
            }
            Path fonts = module.resolve("src/main/resources/fonts");
            try (var files = Files.walk(fonts)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = "fonts/" + relative(fonts, file);
                    var entry = jar.getEntry(name);
                    require(entry != null, "Missing bundled font/notice in application JAR: " + name);
                    try (var in = jar.getInputStream(entry)) {
                        require(Arrays.equals(in.readAllBytes(), Files.readAllBytes(file)),
                                "Changed bundled font/notice in application JAR: " + name);
                    }
                }
            }
        }
    }

    private void verifyDependency(Path jar) throws Exception {
        String name = jar.getFileName().toString();
        Dependency expected = inventory.get(name);
        require(expected != null, "No reviewed license inventory for dependency: " + name);
        require(expected.sha256().equals(sha256(jar)), "Dependency SHA-256 mismatch: " + name);
    }

    private void writeBundle(Path destination) throws IOException {
        // Replace only the generated license directory; unrelated build files remain untouched.
        Path oldLicenses = destination.resolve("licenses");
        if (Files.exists(oldLicenses)) {
            try (var files = Files.walk(oldLicenses)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        for (var entry : bundle.entrySet()) {
            Path path = safeResolve(destination, entry.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, entry.getValue());
        }
    }

    private void verifyBundle(Path destination) throws IOException {
        for (var entry : bundle.entrySet()) {
            require(Arrays.equals(readRequired(safeResolve(destination, entry.getKey())), entry.getValue()),
                    "Missing or outdated distribution notice: " + entry.getKey());
        }
    }

    private void add(String name, byte[] content) {
        require(bundle.putIfAbsent(name, content) == null, "Duplicate notice path: " + name);
    }

    private void addTree(Path root, String prefix, boolean excludeFonts) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (excludeFonts && file.toString().matches("(?i).*\\.(otf|ttf|ttc|woff2?)$")) continue;
                add(prefix + relative(root, file), readRequired(file));
            }
        }
    }

    private static void verifyChecksums(Path root, boolean exhaustive) throws Exception {
        Path sums = root.resolve("SHA256SUMS");
        readRequired(sums);
        Set<Path> listed = new HashSet<>();
        for (String line : Files.readAllLines(sums)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            require(line.matches("[a-f0-9]{64}  .+"), "Invalid SHA256SUMS in " + root);
            Path file = safeResolve(root, line.substring(66));
            require(listed.add(file), "Duplicate checksum path: " + file);
            require(line.substring(0, 64).equals(sha256(file)), "Notice/font SHA-256 mismatch: " + file);
        }
        require(!listed.isEmpty(), "Empty SHA256SUMS in " + root);
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                boolean provenanceReadme = !exhaustive && file.getFileName().toString().equals("README.md");
                require(file.equals(sums) || provenanceReadme || listed.contains(file.toAbsolutePath().normalize()),
                        "Unreviewed notice/font file: " + file);
            }
        }
    }

    private static byte[] readRequired(Path path) throws IOException {
        require(Files.isRegularFile(path), "Required file is missing: " + path);
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length > 0, "Required file is empty: " + path);
        return bytes;
    }

    private static Path safeResolve(Path root, String name) {
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(name).normalize();
        require(!Path.of(name).isAbsolute() && result.startsWith(base) && !result.equals(base),
                "Invalid relative notice path: " + name);
        return result;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536];
            for (int read; (read = in.read(bytes)) != -1;) digest.update(bytes, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isManifest(String name) {
        return name.endsWith("/SHA256SUMS") || name.endsWith("/dependencies.tsv");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
