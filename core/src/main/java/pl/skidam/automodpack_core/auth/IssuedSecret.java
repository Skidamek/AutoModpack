package pl.skidam.automodpack_core.auth;

/** A host secret bound to the player name it was issued to, so every later check authorizes the exact identity the login presented instead of re-deriving one. */
public class IssuedSecret { // a class, not a record - the gson shipped in 1.18 mc cannot read records
	private String secret;
	private Long timestamp;
	private String name;

	public IssuedSecret(Secrets.Secret secret, String name) {
		this.secret = secret.secret();
		this.timestamp = secret.timestamp();
		this.name = name;
	}

	public String secret() {
		return secret;
	}

	public Long timestamp() {
		return timestamp;
	}

	public String name() {
		return name;
	}

	@Override
	public String toString() {
		return "IssuedSecret{name='" + name + "', timestamp=" + timestamp + '}';
	}
}
