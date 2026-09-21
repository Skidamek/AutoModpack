package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The transport's wire window is full: the caller requeues the object without burning retry budget. */
public class WireWindowFullException extends IOException {
	public WireWindowFullException() {
		super("The wire window is full");
	}
}
