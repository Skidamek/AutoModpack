package pl.skidam.automodpack_core.auth;

/** A host secret bound to the player identity it was issued to, so every later check authorizes the exact identity the login presented instead of re-deriving one. */
public class IssuedSecret { // a class, not a record - the gson shipped in 1.18 mc cannot read records
	private String playerId;
	private Long timestamp;
	private String name;

	public IssuedSecret(String playerId, long timestamp, String name) {
		this.playerId = playerId;
		this.timestamp = timestamp;
		this.name = name;
	}

	public String playerId() {
		return playerId;
	}

	public Long timestamp() {
		return timestamp;
	}

	public String name() {
		return name;
	}

	@Override
	public String toString() {
		return "IssuedSecret{playerId='" + playerId + "', name='" + name + "', timestamp=" + timestamp + '}';
	}
}
