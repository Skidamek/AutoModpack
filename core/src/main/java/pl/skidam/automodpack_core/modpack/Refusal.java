package pl.skidam.automodpack_core.modpack;

import java.util.Objects;

/** An operation that produced nothing: the words for the operator and the cause behind them, when there is one. Every outcome's refusal carries one, so the refusal has one shape and one spelling. */
public record Refusal(String detail, Throwable cause) {
	public Refusal {
		detail = Objects.requireNonNull(detail, "detail");
	}
}
