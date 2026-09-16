package pl.skidam.automodpack.networking.content;

/** The single response emitted for the AutoModpack data login query. */
public enum LoginUpdateResponse {
	CONTINUE("false"),
	UPDATE_REQUIRED("true"),
	HOST_ERROR("null");

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
