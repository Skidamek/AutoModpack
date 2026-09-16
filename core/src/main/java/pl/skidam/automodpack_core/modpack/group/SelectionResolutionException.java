package pl.skidam.automodpack_core.modpack.group;

import java.util.List;

public class SelectionResolutionException extends IllegalArgumentException {
	private final List<String> errors;

	public SelectionResolutionException(List<String> errors) {
		super(String.join("; ", errors));
		this.errors = List.copyOf(errors);
	}

	public List<String> errors() {
		return errors;
	}
}
