package codechicken.diffpatch.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by covers1624 on 25/8/20.
 */
public abstract class InputPath {

    private final PathType type;

    protected InputPath(PathType type) {
        this.type = type;
    }

    /**
     * They type of Input this is.
     *
     * @return The type.
     */
    public PathType getType() {
        return type;
    }

    /**
     * Gets if this input should be treated as a singular file,
     * opposed to a folder.
     * <p>
     * Always true for PipePath inputs.
     *
     * @return If this input represents a singular file.
     */
    public abstract boolean isFile();

    /**
     * Gets if this input exists or not.
     *
     * @return If this input exists.
     */
    public abstract boolean exists();

    /**
     * Gets the underlying path representing this input.
     * Unsupported for {@link PathType#PIPE}
     *
     * @return The Path.
     */
    public abstract Path toPath();

    /**
     * Creates a stream representing this input.
     *
     * @return The stream.
     */
    public abstract InputStream open() throws IOException;

    /**
     * Reads all available lines of the input into a List of strings.
     *
     * @return The Lines.
     */
    public abstract List<String> readAllLines() throws IOException;

    /**
     * Gets the name for this input.
     * Unsupported for {@link PathType#PIPE}
     *
     * @return The name.
     */
    public abstract String getName();

    public static class FilePath extends InputPath {

        private final Path path;
        private final OpenOption[] opts;

        public FilePath(Path path, OpenOption... opts) {
            super(PathType.PATH);
            this.path = path;
            this.opts = opts;
        }

        @Override
        public boolean isFile() {
            return Files.isRegularFile(path);
        }

        @Override
        public boolean exists() {
            return Files.exists(path);
        }

        @Override
        public Path toPath() {
            return path;
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path, opts);
        }

        @Override
        public List<String> readAllLines() throws IOException {
            return Files.readAllLines(path);
        }

        @Override
        public String getName() {
            return path.getFileName().toString();
        }
    }

    public static class PipePath extends InputPath {

        private final InputStream pipe;

        public PipePath(InputStream pipe) {
            super(PathType.PIPE);
            this.pipe = pipe;
        }

        @Override
        public InputStream open() {
            return Utils.protectClose(pipe);
        }

        @Override
        public List<String> readAllLines() throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(open()))) {
                return reader.lines().collect(Collectors.toList());
            }
        }

        @Override
        public boolean isFile() {
            return true;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public String getName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path toPath() {
            throw new UnsupportedOperationException();
        }
    }

    //@formatter:off
    public static class NullPath extends InputPath {
        public static final NullPath INSTANCE = new NullPath();
        public NullPath() { super(PathType.NULL); }
        @Override public boolean isFile() { return false; }
        @Override public boolean exists() { return false; }
        @Override public Path toPath() { throw new UnsupportedOperationException(); }
        @Override public InputStream open() { throw new UnsupportedOperationException(); }
        @Override public List<String> readAllLines() { throw new UnsupportedOperationException();  }
        @Override public String getName() { throw new UnsupportedOperationException(); }
    }
    //@formatter:on

}
