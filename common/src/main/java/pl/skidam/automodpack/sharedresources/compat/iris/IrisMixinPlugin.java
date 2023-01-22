package pl.skidam.automodpack.sharedresources.compat.iris;

import pl.skidam.automodpack.sharedresources.compat.CompatMixinPlugin;

import java.util.HashSet;
import java.util.Set;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

public class IrisMixinPlugin implements CompatMixinPlugin {

    @Override
    public Set<String> getRequiredMods() {
        HashSet<String> set = new HashSet<>();
        set.add("iris");
        return set;
    }
}
