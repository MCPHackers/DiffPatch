package codechicken.diffpatch;

import static codechicken.diffpatch.util.Utils.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import codechicken.diffpatch.match.FuzzyLineMatcher;
import codechicken.diffpatch.patch.Patcher;
import codechicken.diffpatch.util.Diff;
import codechicken.diffpatch.util.FileCollector;
import codechicken.diffpatch.util.InputPath;
import codechicken.diffpatch.util.LinesReader;
import codechicken.diffpatch.util.OutputPath;
import codechicken.diffpatch.util.PatchFile;
import codechicken.diffpatch.util.PatchMode;
import codechicken.diffpatch.util.Utils;

/**
 * Created by covers1624 on 11/8/20.
 */
public class PatchOperation {

    private PatchesSummary summary;
	private final boolean verbose;
    private final InputPath basePath;
    private final InputPath patchesPath;
    private final String aPrefix;
    private final String bPrefix;
    private final OutputPath outputPath;
    private final OutputPath rejectsPath;
    private final float minFuzz;
    private final int maxOffset;
    private final PatchMode mode;
    private final String lineSeparator;

    public PatchOperation(boolean verbose, InputPath basePath, InputPath patchesPath, String aPrefix, String bPrefix, OutputPath outputPath, OutputPath rejectsPath, float minFuzz, int maxOffset, PatchMode mode, String lineSeparator) {
        this.verbose = verbose;
        this.basePath = basePath;
        this.patchesPath = patchesPath;
        this.aPrefix = aPrefix;
        this.bPrefix = bPrefix;
        this.outputPath = outputPath;
        this.rejectsPath = rejectsPath;
        this.minFuzz = minFuzz;
        this.maxOffset = maxOffset;
        this.mode = mode;
        this.lineSeparator = lineSeparator;
    }

    public static Builder builder() {
        return new Builder();
    }
    
    public boolean doPatch() throws IOException {
        if (!basePath.exists()) {
            return false;
        }
        if (!patchesPath.exists()) {
            return false;
        }

        FileCollector outputCollector = new FileCollector();
        FileCollector rejectCollector = new FileCollector();
        PatchesSummary summary = new PatchesSummary();
        boolean patchSuccess;

        //Base path and patch path are both singular files.
        if (basePath.isFile() && patchesPath.isFile()) {
            PatchFile patchFile = PatchFile.fromLinesSingle(patchesPath.toString(), patchesPath.readAllLines(), true);
            boolean success = doPatch(outputCollector, rejectCollector, summary, basePath.readAllLines(), patchFile, minFuzz, maxOffset, mode);

            if(outputCollector.getRemoved().isEmpty()) {
	            List<String> output = outputCollector.getSingleFile();
	            List<String> reject = rejectCollector.getSingleFile();
	            try (PrintWriter out = new PrintWriter(outputPath.open())) {
	                out.println(String.join(lineSeparator, output));
	            }
	
	            if (rejectsPath.exists() && !reject.isEmpty()) {
	                try (PrintWriter out = new PrintWriter(rejectsPath.open())) {
	                    out.println(String.join(lineSeparator, reject + lineSeparator));
	                }
	            }
            }
            this.summary = summary;
            return success;
        }

        //Base path is a directory
        Map<String, Path> baseIndex = indexChildren(basePath.toPath());
        Map<String, Path> patchIndex = indexChildren(patchesPath.toPath());
        patchSuccess = doPatch(outputCollector, rejectCollector, summary, baseIndex.keySet(), patchIndex.keySet(), e -> Files.readAllLines(baseIndex.get(e)), e -> Files.readAllLines(patchIndex.get(e)), minFuzz, maxOffset, mode);

        Map<String, byte[]> rawData = new HashMap<>();
        for (Map.Entry<String, Path> entry : baseIndex.entrySet()) {
        	rawData.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
        }
        
        if (Files.exists(outputPath.toPath())) {
            Utils.deleteFolder(outputPath.toPath());
        }
        List<String> removed = outputCollector.getRemoved();
        for (Map.Entry<String, byte[]> entry : rawData.entrySet()) {
            Path path = outputPath.toPath().resolve(entry.getKey());
            if(!removed.contains(entry.getKey())) {
                Files.write(makeParentDirs(path), entry.getValue());
            }
        }

        for(Map.Entry<String, List<String>> entry : outputCollector.get().entrySet()) {
            Path path = outputPath.toPath().resolve(entry.getKey());
            String file = String.join(lineSeparator, entry.getValue());
            Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
        }

        if (!rejectsPath.getType().isNull()) {

            if (Files.exists(rejectsPath.toPath())) {
                Utils.deleteFolder(rejectsPath.toPath());
            }
            for (Map.Entry<String, List<String>> entry : rejectCollector.get().entrySet()) {
                Path path = rejectsPath.toPath().resolve(entry.getKey());
                String file = String.join(lineSeparator, entry.getValue());
                Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
            }
        }
        this.summary = summary;
        return patchSuccess;
    }

    public boolean doPatch(FileCollector oCollector, FileCollector rCollector, PatchesSummary summary, Set<String> bEntries, Set<String> pEntries, LinesReader bFunc, LinesReader pFunc, float minFuzz, int maxOffset, PatchMode mode) {
        List<PatchFile> patchFiles = new ArrayList<>();
        pEntries.stream().forEach(e -> {
        	try {
				patchFiles.addAll(PatchFile.fromLines(e, pFunc.apply(e), true));
			} catch (IOException e1) {
	            verbose("Failed to read patch file: %s", e);
			}
        });
        
        boolean result = true;

        //TODO add summary and rejects
        for (PatchFile patch : patchFiles) {
        	String basePath = patch.getBasePath(aPrefix);
        	try {
        		if(!DEV_NULL.equals(basePath) && !bEntries.contains(basePath)) {
        			summary.missingFiles++;
        			continue;
        		}
	            List<String> lines = DEV_NULL.equals(basePath) ? Collections.emptyList() : bFunc.apply(basePath);
	            result &= doPatch(oCollector, rCollector, summary, lines, patch, minFuzz, maxOffset, mode);
	        } catch (IOException e) {
	            verbose("Failed to read file: %s", basePath);
	        }
        }

        return result;
    }

    public boolean doPatch(FileCollector outputCollector, FileCollector rejectCollector, PatchesSummary summary, List<String> base, PatchFile patchFile, float minFuzz, int maxOffset, PatchMode mode) {
        Patcher patcher = new Patcher(patchFile, base, minFuzz, maxOffset);
        verbose("Patching: " + patchFile.basePath);
        List<Patcher.Result> results = patcher.patch(mode).collect(Collectors.toList());
        List<String> rejectLines = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < results.size(); i++) {
            Patcher.Result result = results.get(i);
            if (result.mode != null) {
                switch (result.mode) {
                    case EXACT:
                        summary.exactMatches++;
                        summary.overallQuality += 100;
                        break;
                    case ACCESS:
                        summary.accessMatches++;
                        summary.overallQuality += 100;
                        break;
                    case OFFSET:
                        summary.offsetMatches++;
                        summary.overallQuality += 100;
                        break;
                    case FUZZY:
                        summary.fuzzyMatches++;
                        summary.overallQuality += (result.fuzzyQuality * 100);
                        break;
                }
            } else {
                summary.failedMatches++;
            }
            verbose(" Hunk %d: %s", i, result.summary());
            if (!result.success) {
                if (!first) {
                    rejectLines.add("");
                }
                first = false;
                rejectLines.add("++++ REJECTED HUNK: " + (i + 1));
                rejectLines.add(result.patch.getHeader());
                result.patch.diffs.stream().map(Diff::toString).forEach(rejectLines::add);
                rejectLines.add("++++ END HUNK");
            }
        }
        List<String> lines = patcher.lines;
        if (!lines.isEmpty()) {
            if (lines.get(lines.size() - 1).isEmpty()) {
                if (!patchFile.noNewLine) {//if we end in a new line and shouldn't have one
                    lines.remove(lines.size() - 1);
                }
            } else {
                lines.add("");
            }
        }
        outputCollector.remove(patchFile.getBasePath(aPrefix));
        outputCollector.consume(patchFile.getPatchedPath(bPrefix), lines);
        if (!rejectLines.isEmpty()) {
            rejectCollector.consume(patchFile.name + ".rej", rejectLines);
            return false;
        }
        return true;
    }
    
    public PatchesSummary getSummary() {
    	return summary;
    }
    
    private void verbose(String str, Object... args) {
    	if(verbose) {
    		System.out.println(String.format(str, args));
    	}
    }

    public static class PatchesSummary {
    	
        public int unchangedFiles;
        public int changedFiles;
        public int addedFiles;
        public int removedFiles;
        public int missingFiles;
        public int failedMatches;
        public int exactMatches;
        public int accessMatches;
        public int offsetMatches;
        public int fuzzyMatches;

        public double overallQuality;

        public void print(PrintStream logger, boolean slim) {
            logger.println("Patch Summary:");
            if (!slim) {
                logger.println(" Unchanged files:  " + unchangedFiles);
                logger.println(" Changed files:    " + changedFiles);
                logger.println(" Added files:      " + addedFiles);
                logger.println(" Removed files:    " + removedFiles);
                logger.println(" Missing files:    " + missingFiles);
            }
            logger.println();
            logger.println(" Failed matches:   " + failedMatches);
            logger.println(" Exact matches:    " + exactMatches);
            logger.println(" Access matches:   " + accessMatches);
            logger.println(" Offset matches:   " + offsetMatches);
            logger.println(" Fuzzy matches:    " + fuzzyMatches);

            logger.println(String.format("Overall Quality   %.2f%%", overallQuality / (failedMatches + exactMatches + accessMatches + offsetMatches + fuzzyMatches)));
        }
    }

    public static class Builder {

        private boolean verbose;
        private InputPath basePath;
        private InputPath patchesPath;
        private OutputPath outputPath;
        private OutputPath rejectsPath = OutputPath.NullPath.INSTANCE;
        private float minFuzz = FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE;
        private int maxOffset = FuzzyLineMatcher.MatchMatrix.DEFAULT_MAX_OFFSET;
        private PatchMode mode = PatchMode.EXACT;
        private String lineSeparator = System.lineSeparator();

        private String aPrefix = "a/";
        private String bPrefix = "b/";

        private Builder() {
        }
        
        public Builder lineSeparator(String lineSeparator) {
        	this.lineSeparator = Objects.requireNonNull(lineSeparator);
        	return this;
        }
        
        public Builder verbose(boolean verbose) {
        	this.verbose = verbose;
        	return this;
        }

        public Builder basePath(InputPath basePath) {
            this.basePath = Objects.requireNonNull(basePath);
            return this;
        }

        public Builder basePath(Path basePath) {
            return basePath(new InputPath.FilePath(Objects.requireNonNull(basePath)));
        }

        public Builder basePath(byte[] basePath) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(basePath));
            return basePath(new InputPath.PipePath(is));
        }

        public Builder patchesPath(InputPath patchesPath) {
            this.patchesPath = Objects.requireNonNull(patchesPath);
            return this;
        }

        public Builder patchesPath(Path patchesPath) {
            return patchesPath(new InputPath.FilePath(Objects.requireNonNull(patchesPath)));
        }

        public Builder patchesPath(byte[] patchesPath) {
            InputStream is = new ByteArrayInputStream(Objects.requireNonNull(patchesPath));
            return patchesPath(new InputPath.PipePath(is));
        }

        public Builder aPrefix(String aPrefix) {
            this.aPrefix = aPrefix;
            return this;
        }

        public Builder bPrefix(String bPrefix) {
            this.bPrefix = bPrefix;
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

        public Builder rejectsPath(OutputPath rejectsPath) {
            this.rejectsPath = Objects.requireNonNull(rejectsPath);
            return this;
        }

        public Builder rejectsPath(Path rejects) {
            return rejectsPath(new OutputPath.FilePath(Objects.requireNonNull(rejects)));
        }

        public Builder rejectsPath(OutputStream rejects) {
            return rejectsPath(new OutputPath.PipePath(Objects.requireNonNull(rejects)));
        }

        public Builder minFuzz(float minFuzz) {
            this.minFuzz = minFuzz;
            return this;
        }

        public Builder maxOffset(int maxOffset) {
            this.maxOffset = maxOffset;
            return this;
        }

        public Builder mode(PatchMode mode) {
            this.mode = Objects.requireNonNull(mode);
            return this;
        }

        public PatchOperation build() {
            if (basePath == null) {
                throw new IllegalStateException("basePath not set.");
            }
            if (patchesPath == null) {
                throw new IllegalStateException("patchesPath not set.");
            }
            if (outputPath == null) {
                throw new IllegalStateException("output not set.");
            }
            return new PatchOperation(verbose, basePath, patchesPath, aPrefix, bPrefix, outputPath, rejectsPath, minFuzz, maxOffset, mode, lineSeparator);
        }

    }
}
