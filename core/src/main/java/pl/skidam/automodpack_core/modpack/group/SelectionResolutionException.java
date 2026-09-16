package pl.skidam.automodpack_core.modpack.group;

import java.util.List;

public class SelectionResolutionException extends IllegalArgumentException {
	private final List<String> errors;
	private final ResolvedSelection resolution;

	public SelectionResolutionException(List<String> errors) {
		this(errors, null);
	}

	public SelectionResolutionException(List<String> errors, ResolvedSelection resolution) {
		super(String.join("; ", errors));
		this.errors = List.copyOf(errors);
		this.resolution = resolution;
	}

	public List<String> errors() {
		return errors;
	}

	public ResolvedSelection resolution() {
		return resolution;
	}
}
