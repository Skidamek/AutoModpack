package pl.skidam.automodpack_core.protocol.netty;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

/** The activity command's read model: the tracker's spans with object hashes resolved against the current generation's pack paths. */
public final class ActivityView {

	private final ActivityTracker tracker;
	private final Function<String, Optional<Path>> routePaths;
	private final LongSupplier lastWriteThroughput;

	public ActivityView(ActivityTracker tracker, Function<String, Optional<Path>> routePaths, LongSupplier lastWriteThroughput) {
		this.tracker = tracker;
		this.routePaths = routePaths;
		this.lastWriteThroughput = lastWriteThroughput;
	}

	public ActivityTracker.Snapshot snapshot() {
		Map<String, String> names = new HashMap<>();
		routePaths.apply(GenerationHosting.HEAD_DOCUMENT_KEY).ifPresent(head -> {
			GenerationJsons.HeadDocumentFields document = ModpackContentTools.readHeadDocument(head);
			if (document != null) document.policy.categories.forEach((category, groups) -> groups.forEach((group, fields) -> fields.files.forEach((path, file) -> names.put(file.sha1, path))));
		});
		long throughput = lastWriteThroughput.getAsLong();
		if (throughput >= 0) tracker.writeThroughput(throughput);
		return tracker.snapshot(names);
	}
}
