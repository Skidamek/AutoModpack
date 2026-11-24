package pl.skidam.automodpack.networking.content;

public enum LoginUpdateResponse {
	CONTINUE((byte) 0),
	UPDATE_REQUIRED((byte) 1),
	HOST_ERROR((byte) 2),
	CLIENT_REJECTED((byte) 3),
	CLIENT_DECLINED((byte) 4),
	JOIN_CANCELLED((byte) 5),
	CONTRACT_MISMATCH((byte) 6),
	LOADER_MISMATCH((byte) 7),
	GENERATING((byte) 8);

	private final byte code;

	LoginUpdateResponse(byte code) {
		this.code = code;
	}

	public byte code() {
		return code;
	}

	public static LoginUpdateResponse fromCode(byte code) {
		for (LoginUpdateResponse response : values()) if (response.code == code) return response;
		return HOST_ERROR;
	}
}
