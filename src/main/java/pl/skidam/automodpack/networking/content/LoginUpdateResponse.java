package pl.skidam.automodpack.networking.content;

/** The single response emitted for the AutoModpack data login query; both ends run the same mod version, enforced by the handshake before this query exists. */
public enum LoginUpdateResponse {
	CONTINUE("false"),
	UPDATE_REQUIRED("true"),
	HOST_ERROR("null"),
	/** The client refused the host's certificate, so the failure lives on the player's side of the connection. */
	CLIENT_REJECTED("rejected"),
	/** The player dismissed the certificate verification prompt and the client ended the login for it. */
	CLIENT_DECLINED("declined");

	private final String wireValue;

	LoginUpdateResponse(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	public static LoginUpdateResponse fromWire(String wireValue) {
		for (LoginUpdateResponse response : values()) if (response.wireValue.equals(wireValue)) return response;
		return HOST_ERROR;
	}
}
