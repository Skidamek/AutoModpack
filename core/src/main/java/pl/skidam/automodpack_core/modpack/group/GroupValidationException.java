package pl.skidam.automodpack_core.modpack.group;

import java.util.List;

public class GroupValidationException extends IllegalArgumentException {
	private final List<String> errors;

	public GroupValidationException(List<String> errors) {
		super(String.join("; ", errors));
		this.errors = List.copyOf(errors);
	}

	public List<String> errors() {
		return errors;
	}
}
