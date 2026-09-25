package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/**
 * A throttled host's answer (503, 429): framed and consumed, so the lane stays aligned and only this take failed. The
 * provider's {@code Retry-After} in milliseconds when it sent one (seconds-form, clamped to the network timeout),
 * negative when it did not - the retry ladder waits it out instead of burning attempts into the throttle window.
 */
public class HostThrottleException extends IOException {
	private final long retryAfterMillis;

	public HostThrottleException(int status, long retryAfterMillis) {
		super("HTTP " + status + " throttled" + (retryAfterMillis > 0 ? "; Retry-After " + retryAfterMillis + " ms" : ""));
		this.retryAfterMillis = retryAfterMillis;
	}

	public long retryAfterMillis() {
		return retryAfterMillis;
	}
}
