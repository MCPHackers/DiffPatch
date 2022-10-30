package codechicken.diffpatch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import codechicken.diffpatch.diff.Differ;
import codechicken.diffpatch.diff.PatienceDiffer;
import codechicken.diffpatch.util.FileCollector;
import codechicken.diffpatch.util.InputPath;
import codechicken.diffpatch.util.LinesReader;
import codechicken.diffpatch.util.Operation;
import codechicken.diffpatch.util.OutputPath;
import codechicken.diffpatch.util.PatchFile;
import codechicken.diffpatch.util.Utils;

import static codechicken.diffpatch.util.Utils.*;

/**
 * Handles doing a Diff operation from the CLI.
 * <p>
 * Created by covers1624 on 11/8/20.
 */
public class DiffOperation {

    public static final String DEV_NULL = "/dev/null";

    private final DiffSummary summary = new DiffSummary();
	private final boolean verbose;
    private final InputPath aPath;
    private final InputPath bPath;
    private final String aPrefix;
    private final String bPrefix;
    private final boolean autoHeader;
    private final int context;
    private final OutputPath outputPath;

    public DiffOperation(boolean verbose, InputPath aPath, InputPath bPath, String aPrefix, String bPrefix, boolean autoHeader, int context, OutputPath outputPath) {
        this.verbose = verbose;
    	this.aPath = aPath;
        this.bPath = bPath;
        this.aPrefix = aPrefix;
        this.bPrefix = bPrefix;
        this.autoHeader = autoHeader;
        this.context = context;
        this.outputPath = outputPath;
    }

    public static Builder builder() {
        return new Builder();
    }
    
    public boolean doDiff() throws IOException {
        FileCollector patches = new FileCollector();
        DiffSummary summary = new DiffSummary();
        //Base path and patch path are both singular files.
        if (aPath.isFile() && bPath.isFile()) {
            List<String> lines = doDiff(summary, aPath.toPath().toString(), bPath.toPath().toString(), aPath.readAllLines(), bPath.readAllLines(), context, autoHeader);
            boolean changes = false;
            if (!lines.isEmpty()) {
                changes = true;
                try (PrintWriter out = new PrintWriter(outputPath.open())) {
                    out.println(String.join(System.lineSeparator(), lines) + System.lineSeparator());
                }
            }
            return changes;
        } else if (!aPath.isFile() && !bPath.isFile()) {
            //Both inputs are directories.
            Map<String, Path> aIndex = indexChildren(aPath.toPath());
            Map<String, Path> bIndex = indexChildren(bPath.toPath());
            doDiff(patches, summary, aIndex.keySet(), bIndex.keySet(), e -> Files.readAllLines(aIndex.get(e)), e -> Files.readAllLines(bIndex.get(e)), context, autoHeader);
        } else {
        	//Inputs are a directory and a file
        	return false;
        }
        boolean changes = false;
        if (!patches.isEmpty()) {
            changes = true;
            if (outputPath.getType().isPipe()) {
                try (PrintWriter out = new PrintWriter(outputPath.open())) {
                    for (List<String> lines : patches.values()) {
                        lines.forEach(out::println);
                    }
                }
            } else {
                if (Files.exists(outputPath.toPath())) {
                    Utils.deleteFolder(outputPath.toPath());
                }
                for (Map.Entry<String, List<String>> entry : patches.get().entrySet()) {
                    Path path = outputPath.toPath().resolve(entry.getKey());
                    Files.write(makeParentDirs(path), entry.getValue());
                }
            }
        }
        return changes;
    }

    public void doDiff(FileCollector patches, DiffSummary summary, Set<String> aEntries, Set<String> bEntries, LinesReader aFunc, LinesReader bFunc, int context, boolean autoHeader) {
        List<String> added = bEntries.stream().filter(e -> !aEntries.contains(e)).sorted().collect(Collectors.toList());
        List<String> common = aEntries.stream().filter(bEntries::contains).sorted().collect(Collectors.toList());
        List<String> removed = aEntries.stream().filter(e -> !bEntries.contains(e)).sorted().collect(Collectors.toList());
        String aPrefix = this.aPrefix == null ? "" : StringUtils.appendIfMissing(this.aPrefix.isEmpty() ? "a" : this.aPrefix, "/");
        String bPrefix = this.bPrefix == null ? "" : StringUtils.appendIfMissing(this.bPrefix.isEmpty() ? "b" : this.bPrefix, "/");
        for (String file : added) {
            try {
                String bName = bPrefix + StringUtils.removeStart(file, "/");
                List<String> aLines = Collections.emptyList();
                List<String> bLines = bFunc.apply(file);
                List<String> patchLines = doDiff(summary, null, bName, aLines, bLines, context, autoHeader);
                if (!patchLines.isEmpty()) {
                    summary.addedFiles++;
                    patches.consume(file + ".patch", patchLines);
                } else {
                    summary.unchangedFiles++;
                }
            } catch (IOException e) {
                verbose("Failed to read file: %s", file);
            }
        }
        for (String file : common) {
            try {
                String aName = aPrefix + StringUtils.removeStart(file, "/");
                String bName = bPrefix + StringUtils.removeStart(file, "/");
                List<String> aLines = aFunc.apply(file);
                List<String> bLines = bFunc.apply(file);
                List<String> patchLines = doDiff(summary, aName, bName, aLines, bLines, context, autoHeader);
                if (!patchLines.isEmpty()) {
                    summary.changedFiles++;
                    patches.consume(file + ".patch", patchLines);
                } else {
                    summary.unchangedFiles++;
                }
            } catch (IOException e) {
                verbose("Failed to read file: %s", file);
            }
        }
        for (String file : removed) {
            try {
                String aName = aPrefix + StringUtils.removeStart(file, "/");
                List<String> aLines = aFunc.apply(file);
                List<String> bLines = Collections.emptyList();
                List<String> patchLines = doDiff(summary, aName, null, aLines, bLines, context, autoHeader);
                if (!patchLines.isEmpty()) {
                    summary.removedFiles++;
                    patches.consume(file + ".patch", patchLines);
                } else {
                    summary.unchangedFiles++;
                }
            } catch (IOException e) {
                verbose("Failed to read file: %s", file);
            }
        }
    }

    public List<String> doDiff(DiffSummary summary, String aName, String bName, List<String> aLines, List<String> bLines, int context, boolean autoHeader) {
        PatienceDiffer differ = new PatienceDiffer();
        PatchFile patchFile = new PatchFile();
        patchFile.basePath = aName != null ? aName : DEV_NULL;
        patchFile.patchedPath = bName != null ? bName : DEV_NULL;
        if (aLines.isEmpty() && bLines.isEmpty()) {
            patchFile.patches = Collections.emptyList();
        } else if (aLines.isEmpty()) {
            patchFile.patches = Differ.makeFileAdded(bLines);
        } else if (bLines.isEmpty()) {
            patchFile.patches = Differ.makeFileRemoved(aLines);
        } else {
            patchFile.patches = differ.makePatches(aLines, bLines, context, true);
        }
        if (patchFile.patches.isEmpty()) {
            verbose("%s -> %s\n No changes.", aName, bName);
            return Collections.emptyList();
        }
        long added = patchFile.patches.stream()//
                .flatMap(e -> e.diffs.stream())//
                .filter(e -> e.op == Operation.INSERT)//
                .count();
        long removed = patchFile.patches.stream()//
                .flatMap(e -> e.diffs.stream())//
                .filter(e -> e.op == Operation.DELETE)//
                .count();
        summary.addedLines += added;
        summary.removedLines += removed;
        verbose("%s -> %s\n %d Added.\n %d Removed.", aName, bName, added, removed);
        return patchFile.toLines(autoHeader);
    }
    
    public DiffSummary getSummary() {
    	return summary;
    }

    public static class DiffSummary {

        public int unchangedFiles;
        public int addedFiles;
        public int changedFiles;
        public int removedFiles;

        public long addedLines;
        public long removedLines;

        public void print(PrintStream logger, boolean slim) {
            logger.println("Diff Summary:");
            if (!slim) {
                logger.println(" UnChanged files: " + unchangedFiles);
                logger.println(" Added files:     " + addedFiles);
                logger.println(" Changed files:   " + changedFiles);
                logger.println(" Removed files:   " + removedFiles);
            }

            logger.println(" Added lines:     " + addedLines);
            logger.println(" Removed lines:   " + removedLines);
        }
    }
    
    private void verbose(Object... s) {
    	if(verbose) {
    		System.out.println(s);
    	}
    }

    public static class Builder {

    	private boolean verbose;
        private InputPath aPath;
        private InputPath bPath;
        private boolean autoHeader;
        private int context = Differ.DEFAULT_CONTEXT;
        private OutputPath outputPath;
        private String aPrefix = "a/";
        private String bPrefix = "b/";

        private Builder() {
        }

        public Builder verbose(boolean verbose) {
        	this.verbose = verbose;
        	return this;
        }

        public Builder aPath(InputPath aPath) {
            if (this.aPath != null) {
                throw new IllegalStateException("Unable to replace aPath.");
            }
            this.aPath = Objects.requireNonNull(aPath);
            return this;
        }

        public Builder aPath(Path aPath) {
            return aPath(new InputPath.FilePath(aPath));
        }

        public Builder aPath(byte[] aPath) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(aPath));
            return aPath(new InputPath.PipePath(is));
        }

        public Builder bPath(InputPath bPath) {
            if (this.bPath != null) {
                throw new IllegalStateException("Unable to replace bPath.");
            }
            this.bPath = Objects.requireNonNull(bPath);
            return this;
        }

        public Builder aPrefix(String aPrefix) {
            this.aPrefix = aPrefix;
            return this;
        }

        public Builder bPath(Path aPath) {
            return bPath(new InputPath.FilePath(aPath));
        }

        public Builder bPath(byte[] aPath) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(aPath));
            return bPath(new InputPath.PipePath(is));
        }

        public Builder bPrefix(String bPrefix) {
            this.bPrefix = bPrefix;
            return this;
        }

        public Builder autoHeader(boolean autoHeader) {
            this.autoHeader = autoHeader;
            return this;
        }

        public Builder context(int context) {
            this.context = context;
            return this;
        }

        public Builder outputPath(OutputPath outputPath) {
            this.outputPath = Objects.requireNonNull(outputPath);
            return this;
        }

        public Builder outputPath(Path output) {
            return outputPath(new OutputPath.FilePath(Objects.requireNonNull(output)));
        }

        public Builder outputPath(OutputStream output) {
            return outputPath(new OutputPath.PipePath(Objects.requireNonNull(output)));
        }

        public DiffOperation build() {
            if (aPath == null) {
                throw new IllegalStateException("aPath not set.");
            }
            if (bPath == null) {
                throw new IllegalStateException("bPath not set.");
            }
            if (outputPath == null) {
                throw new IllegalStateException("output not set.");
            }
            return new DiffOperation(verbose, aPath, bPath, aPrefix, bPrefix, autoHeader, context, outputPath);
        }

    }
}
