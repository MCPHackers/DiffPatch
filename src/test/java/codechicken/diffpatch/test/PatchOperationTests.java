package codechicken.diffpatch.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import codechicken.diffpatch.PatchOperation;

/**
 * Created by covers1624 on 11/2/21.
 */
public class PatchOperationTests {

    @Test
    public void testFolderToFolder() throws Throwable {
        Path tempDir = Files.createTempDirectory("dir_test");
        tempDir.toFile().deleteOnExit();
        Path orig = tempDir.resolve("orig");
        Path cmp = tempDir.resolve("cmp");
        Path src = tempDir.resolve("src");
        Path patches = tempDir.resolve("patches");
        copyResource("/data/orig/PatchFile.java", orig.resolve("PatchFile.java"));
        copyResource("/data/src/PatchFile.java", cmp.resolve("PatchFile.java"));
        copyResource("/data/patches/PatchFile.java.patch", patches.resolve("PatchFile.java.patch"));
        boolean result = PatchOperation.builder()
                .basePath(orig)
                .outputPath(src)
                .patchesPath(patches)
                .build()
                .doPatch();
        assertTrue(result);
        assertTrue(Files.exists(src.resolve("PatchFile.java")));
        List<String> output = Files.readAllLines(src.resolve("PatchFile.java"));
        List<String> original = Files.readAllLines(cmp.resolve("PatchFile.java"));
        assertEquals(output, original);
    }

    private static void copyResource(String resource, Path to) throws IOException {
        to = to.toAbsolutePath();
        Files.createDirectories(to.getParent());
        try (InputStream is = DiffOperationTests.class.getResourceAsStream(resource)) {
            Files.copy(is, to);
        }
    }
}
