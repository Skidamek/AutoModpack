package pl.skidam.automodpack.utils;

import java.nio.file.Path;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

public interface FileResourcepackProviderProxy {
    void sharedresources$setPacksFolder(Path folder);

    Path sharedresources$getPacksFolder();
}