package pl.skidam.automodpack_core.protocol.compression;

public enum CompressionType {
	NONE((byte) 0x00),
	ZSTD((byte) 0x01),
	GZIP((byte) 0x02),
	SNAPPY((byte) 0x03),
	LZ4((byte) 0x04),
	LZO((byte) 0x05);

	private final byte wireId;

	CompressionType(byte wireId) {
		this.wireId = wireId;
	}

	public byte wireId() {
		return wireId;
	}

	public static CompressionType fromWireId(byte wireId) {
		for (CompressionType type : values()) {
			if (type.wireId == wireId) return type;
		}
		throw new IllegalArgumentException("Unknown compression type: " + Byte.toUnsignedInt(wireId));
	}
}
