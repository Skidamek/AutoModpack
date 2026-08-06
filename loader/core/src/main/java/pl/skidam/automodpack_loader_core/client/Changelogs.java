package pl.skidam.automodpack_loader_core.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.update.UpdatePlan;

public class Changelogs {
	private final Map<UpdatePlan.FileKey, List<String>> updatedFiles = new LinkedHashMap<>();
	private final Set<UpdatePlan.FileKey> removedFiles = new LinkedHashSet<>();

	public Map<UpdatePlan.FileKey, List<String>> updatedFiles() {
		return Collections.unmodifiableMap(updatedFiles);
	}

	public Set<UpdatePlan.FileKey> removedFiles() {
		return Collections.unmodifiableSet(removedFiles);
	}

	public void clear() {
		updatedFiles.clear();
		removedFiles.clear();
	}

	public void recordUpdated(UpdatePlan.FileKey file, List<String> mainPageUrls) {
		updatedFiles.put(file, List.copyOf(mainPageUrls));
	}

	public void recordRemoved(UpdatePlan.FileKey file) {
		removedFiles.add(file);
	}
}
