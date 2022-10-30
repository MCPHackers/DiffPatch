package codechicken.diffpatch.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import codechicken.diffpatch.DiffOperation;

/**
 * These tests assume that ArchiveReader and ArchiveWriter behave the same across all formats,
 * and are tested separately, we simply use zip here for convenience.
 * <p>
 * Created by covers1624 on 11/2/21.
 */
public class DiffOperationTests {

    @Test
    public void testFolderToFolder() throws Throwable {
        Path tempDir = Files.createTempDirectory("dir_test");
        tempDir.toFile().deleteOnExit();
        Path orig = tempDir.resolve("orig");
        Path src = tempDir.resolve("src");
        Path patches = tempDir.resolve("patches");
        copyResource("/data/orig/PatchFile.java", orig.resolve("PatchFile.java"));
        copyResource("/data/src/PatchFile.java", src.resolve("PatchFile.java"));
        boolean result = DiffOperation.builder()
                .aPath(orig)
                .bPath(src)
                .outputPath(patches)
                .build()
                .doDiff();
        assertTrue(result);
        assertTrue(Files.exists(patches.resolve("PatchFile.java.patch")));
    }

    private static void copyResource(String resource, Path to) throws IOException {
        to = to.toAbsolutePath();
        Files.createDirectories(to.getParent());
        try (InputStream is = DiffOperationTests.class.getResourceAsStream(resource)) {
            Files.copy(is, to);
        }
    }
}
