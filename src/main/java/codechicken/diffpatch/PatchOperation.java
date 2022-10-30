package codechicken.diffpatch;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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

import static codechicken.diffpatch.util.Utils.*;
import static java.lang.System.lineSeparator;
import static org.apache.commons.lang3.StringUtils.removeStart;

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
    private final String patchesPrefix;

    public PatchOperation(boolean verbose, InputPath basePath, InputPath patchesPath, String aPrefix, String bPrefix, OutputPath outputPath, OutputPath rejectsPath, float minFuzz, int maxOffset, PatchMode mode, String patchesPrefix) {
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
        this.patchesPrefix = patchesPrefix;
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
            PatchFile patchFile = PatchFile.fromLines(patchesPath.toString(), patchesPath.readAllLines(), true);
            boolean success = doPatch(outputCollector, rejectCollector, summary, basePath.toString(), basePath.readAllLines(), patchFile, minFuzz, maxOffset, mode);
            List<String> output = outputCollector.getSingleFile();
            List<String> reject = rejectCollector.getSingleFile();
            try (PrintWriter out = new PrintWriter(outputPath.open())) {
                out.println(String.join(lineSeparator(), output));
            }

            if (rejectsPath.exists() && !reject.isEmpty()) {
                try (PrintWriter out = new PrintWriter(rejectsPath.open())) {
                    out.println(String.join(lineSeparator(), reject + lineSeparator()));
                }
            }
            return success;
        } else if (!basePath.isFile() && !patchesPath.isFile()) {
            //Both inputs are directories.
            Map<String, Path> baseIndex = indexChildren(basePath.toPath());
            Map<String, Path> patchIndex = indexChildren(patchesPath.toPath(), patchesPrefix);
            patchSuccess = doPatch(outputCollector, rejectCollector, summary, baseIndex.keySet(), patchIndex.keySet(), e -> Files.readAllLines(baseIndex.get(e)), e -> Files.readAllLines(patchIndex.get(e)), minFuzz, maxOffset, mode);
        } else {
        	//Inputs are a directory and a file
        	return false;
        }


        if (Files.exists(outputPath.toPath())) {
            Utils.deleteFolder(outputPath.toPath());
        }
        for (Map.Entry<String, List<String>> entry : outputCollector.get().entrySet()) {
            Path path = outputPath.toPath().resolve(entry.getKey());
            String file = String.join(lineSeparator(), entry.getValue());
            Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
        }

        if (!rejectsPath.getType().isNull()) {

            if (Files.exists(rejectsPath.toPath())) {
                Utils.deleteFolder(rejectsPath.toPath());
            }
            for (Map.Entry<String, List<String>> entry : rejectCollector.get().entrySet()) {
                Path path = rejectsPath.toPath().resolve(entry.getKey());
                String file = String.join(lineSeparator(), entry.getValue());
                Files.write(makeParentDirs(path), file.getBytes(StandardCharsets.UTF_8));
            }
        }
        this.summary = summary;
        return patchSuccess;
    }

    public boolean doPatch(FileCollector oCollector, FileCollector rCollector, PatchesSummary summary, Set<String> bEntries, Set<String> pEntries, LinesReader bFunc, LinesReader pFunc, float minFuzz, int maxOffset, PatchMode mode) {
        Map<String, PatchFile> patchFiles = pEntries.stream()
                .map(e -> {
					try {
						return PatchFile.fromLines(e, pFunc.apply(e), true);
					} catch (IOException e1) {
			            verbose("Failed to read patch file: %s", e);
						return null;
					}
				})
                .collect(Collectors.toMap(e -> {
                            if (e.patchedPath == null) {
                                return e.name.substring(0, e.name.lastIndexOf(".patch"));
                            }
                            if (e.patchedPath.startsWith(bPrefix)) {
                                return removeStart(e.patchedPath.substring(bPrefix.length()), "/");
                            }
                            if (DEV_NULL.equals(e.patchedPath)) {
                                return e.basePath;
                            }
                            return e.patchedPath;
                        },
                        Function.identity()));

        List<String> notPatched = bEntries.stream().filter(e -> !patchFiles.containsKey(e)).sorted().collect(Collectors.toList());
        List<String> patchedFiles = bEntries.stream().filter(patchFiles::containsKey).sorted().collect(Collectors.toList());
        List<String> addedFiles = patchFiles.keySet().stream().filter(e -> !bEntries.contains(e)).sorted().collect(Collectors.toList());
        
        boolean result = true;
        for (String file : notPatched) {
        	try {
	            summary.unchangedFiles++;
	            List<String> lines = bFunc.apply(file);
	            oCollector.consume(file, lines);
	        } catch (IOException e) {
	            verbose("Failed to read file: %s", file);
	        }
        }

        for (String file : patchedFiles) {
        	try {
	            PatchFile patchFile = patchFiles.get(file);
	            if(!DEV_NULL.equals(patchFile.patchedPath)) {
	            	summary.changedFiles++;
	            } else {
	            	summary.removedFiles++;
	            }
	            List<String> baseLines = bFunc.apply(file);
	            result &= doPatch(oCollector, rCollector, summary, file, baseLines, patchFile, minFuzz, maxOffset, mode);
	        } catch (IOException e) {
	            verbose("Failed to read file: %s", file);
	        }
        }

        for (String file : addedFiles) {
            summary.addedFiles++;
            PatchFile patchFile = patchFiles.get(file);
            result &= doPatch(oCollector, rCollector, summary, file, Collections.emptyList(), patchFile, minFuzz, maxOffset, mode);
        }

        //TODO do some more tweaking and check /dev/null
//        for (String file : removedFiles) {
//            summary.missingFiles++;
//            PatchFile patchFile = patchFiles.get(file);
//            List<String> lines = new ArrayList<>(patchFile.toLines(false));
//            lines.add(0, "++++ Target missing");
//            verbose("Missing patch target for %s", patchFile.name);
//            rCollector.consume(patchFile.name, lines);
//            result = false;
//        }

        return result;
    }

    public boolean doPatch(FileCollector outputCollector, FileCollector rejectCollector, PatchesSummary summary, String baseName, List<String> base, PatchFile patchFile, float minFuzz, int maxOffset, PatchMode mode) {
        Patcher patcher = new Patcher(patchFile, base, minFuzz, maxOffset);
        verbose("Patching: " + baseName);
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
        if(!DEV_NULL.equals(patchFile.patchedPath)) {
            outputCollector.consume(baseName, lines);
        }
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
                logger.println(" Un-changed files: " + unchangedFiles);
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
        private String patchesPrefix = "";

        private String aPrefix = "a/";
        private String bPrefix = "b/";

        private Builder() {
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

        public Builder patchesPrefix(String patchesPrefix) {
            this.patchesPrefix = Objects.requireNonNull(patchesPrefix);
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
            return new PatchOperation(verbose, basePath, patchesPath, aPrefix, bPrefix, outputPath, rejectsPath, minFuzz, maxOffset, mode, patchesPrefix);
        }

    }
}
