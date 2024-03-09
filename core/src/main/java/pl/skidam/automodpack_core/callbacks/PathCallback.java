package pl.skidam.automodpack_core.callbacks;

import java.nio.file.Path;

public interface PathCallback {
    void run(Path path) throws Exception;
}
