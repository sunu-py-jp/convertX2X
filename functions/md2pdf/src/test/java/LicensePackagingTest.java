import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Distribution regression tests, identical in the independent Java functions. */
class LicensePackagingTest {
    @TempDir static Path compiled;
    @TempDir Path root;
    Path classpath, classes, stage, dependency;

    @BeforeAll static void compileChecker() {
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", compiled.toString(), "scripts/PackageLicenses.java"));
    }

    @BeforeEach void fixture() throws Exception {
        classpath = root.resolve("runtime.txt");
        classes = root.resolve("classes");
        stage = root.resolve("deployment");
        dependency = root.resolve("dependency-1.0.jar");
        Files.writeString(dependency, "unmodified dependency fixture");
        Files.writeString(classpath, dependency.toString());
        write("LICENSE", "Application MIT license fixture");
        write("NOTICE.md", "Application notices fixture");
        write("third-party/java/dep/LICENSE", "Copyright Example Author. MIT license fixture.");
        write("third-party/java/dep/NOTICE", "Additional upstream attribution fixture");
        write("third-party/java/SOURCE.md", "Fixture provenance");
        write("third-party/java/dependencies.tsv",
                "dependency-1.0.jar\t" + hash(dependency) + "\tdep/LICENSE,dep/NOTICE\n");
        checksumTree(root.resolve("third-party/java"));
        write("third-party/maven-wrapper/LICENSE", "Wrapper license fixture");
        write("third-party/maven-wrapper/NOTICE", "Wrapper notice fixture");
        checksumTree(root.resolve("third-party/maven-wrapper"));
        write("src/main/resources/fonts/example/font.ttf", "font fixture");
        write("src/main/resources/fonts/example/OFL.txt", "Font copyright and OFL fixture");
        checksumTree(root.resolve("src/main/resources/fonts/example"));
    }

    @Test void includesOriginalNoticesInJarAndStandaloneBundle() throws Exception {
        packageFixture();
        succeeds("stage");
        succeeds("check");
        String catalog = Files.readString(stage.resolve("THIRD-PARTY-NOTICES.txt"));
        for (String expected : List.of("Copyright Example Author", "Additional upstream attribution",
                "Wrapper notice fixture", "Font copyright and OFL")) assertTrue(catalog.contains(expected));
        assertTrue(Files.exists(stage.resolve("licenses/java/SHA256SUMS")));
        assertFalse(Files.exists(stage.resolve("licenses/fonts/example/font.ttf")),
                "Font binaries should remain in the application JAR, not be duplicated");
    }

    @Test void rejectsNewDependencyWithoutReviewedNotice() throws Exception {
        Path added = root.resolve("new-1.0.jar");
        Files.writeString(added, "new dependency");
        Files.writeString(classpath, dependency + File.pathSeparator + added);
        fails("prepare", "No reviewed license inventory");
    }

    @Test void rejectsChangedDependencyBytesUnderSameVersion() throws Exception {
        Files.writeString(dependency, "different jar");
        fails("prepare", "Dependency SHA-256 mismatch");
    }

    @Test void rejectsMissingOrChangedOriginalLicenseText() throws Exception {
        Path license = root.resolve("third-party/java/dep/LICENSE");
        Files.writeString(license, "license name only");
        fails("prepare", "Notice/font SHA-256 mismatch");
        Files.delete(license);
        fails("prepare", "dep");
    }

    @Test void rejectsRemovedRuntimeDependencyUntilInventoryIsReviewed() throws Exception {
        Files.writeString(classpath, "");
        fails("prepare", "Empty runtime classpath");
    }

    @Test void rejectsUnreviewedFontAdditionsAndChangedWrapperNotice() throws Exception {
        Path newFont = root.resolve("src/main/resources/fonts/example/extra.otf");
        Files.writeString(newFont, "unreviewed font");
        fails("prepare", "Unreviewed notice/font file");
        Files.delete(newFont);
        Files.writeString(root.resolve("third-party/maven-wrapper/NOTICE"), "truncated");
        fails("prepare", "Notice/font SHA-256 mismatch");
    }

    @Test void rejectsDistributionWithMissingNoticeOrExtraJar() throws Exception {
        packageFixture();
        succeeds("stage");
        Files.delete(stage.resolve("licenses/java/dep/NOTICE"));
        fails("check", "Required file is missing");
        succeeds("stage");
        Files.writeString(stage.resolve("lib/extra.jar"), "unreviewed");
        fails("check", "No reviewed license inventory");
    }

    @Test void rejectsApplicationJarWithoutEmbeddedNotices() throws Exception {
        packageFixture();
        try (var zip = new ZipOutputStream(Files.newOutputStream(stage.resolve("app.jar")))) {
            zip.putNextEntry(new ZipEntry("placeholder"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        fails("stage", "Missing notice in application JAR");
    }

    private void packageFixture() throws Exception {
        succeeds("prepare");
        Files.createDirectories(stage.resolve("lib"));
        Files.copy(dependency, stage.resolve("lib/dependency-1.0.jar"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(stage.resolve("app.jar")))) {
            zipTree(zip, classes, "");
            zipTree(zip, root.resolve("src/main/resources/fonts"), "fonts/");
        }
    }

    private void zipTree(ZipOutputStream zip, Path base, String prefix) throws Exception {
        try (var files = Files.walk(base)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(prefix + base.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void succeeds(String command) throws Exception {
        var result = run(command);
        assertEquals(0, result.exitCode(), result.output());
    }

    private void fails(String command, String expected) throws Exception {
        var result = run(command);
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(expected), result.output());
    }

    record Result(int exitCode, String output) {}
    private Result run(String command) throws Exception {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", compiled.toString(), "PackageLicenses", command, root.toString(),
                classpath.toString(), (command.equals("prepare") ? classes : stage).toString()));
        if (!command.equals("prepare")) args.add("app.jar");
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private void write(String name, String value) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static void checksumTree(Path directory) throws Exception {
        var sums = new StringBuilder();
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                sums.append(hash(path)).append("  ")
                        .append(directory.relativize(path).toString().replace('\\', '/')).append("\n");
            }
        }
        Files.writeString(directory.resolve("SHA256SUMS"), sums);
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
