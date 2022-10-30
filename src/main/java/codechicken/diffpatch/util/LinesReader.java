package codechicken.diffpatch.util;

import java.io.IOException;
import java.util.List;

public interface LinesReader {

    List<String> apply(String path) throws IOException;
}