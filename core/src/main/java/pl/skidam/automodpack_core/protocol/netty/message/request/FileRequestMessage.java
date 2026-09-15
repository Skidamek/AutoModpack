package pl.skidam.automodpack_core.protocol.netty.message.request;

import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_REQUEST_TYPE;

import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;

public class FileRequestMessage extends ProtocolMessage {
	private final byte[] fileHash;
	private final byte[] expectedSha1;
	private final long offset;
	private final Long endInclusive;

	/** Plain object request: no conditional hash, no range. */
	public FileRequestMessage(byte version, byte[] secret, byte[] fileHash) {
		this(version, secret, fileHash, null, 0, null);
	}

	public FileRequestMessage(byte version, byte[] secret, byte[] fileHash, byte[] expectedSha1, long offset, Long endInclusive) {
		super(version, FILE_REQUEST_TYPE, secret);
		this.fileHash = fileHash;
		this.expectedSha1 = expectedSha1;
		this.offset = offset;
		this.endInclusive = endInclusive;
	}

	public byte[] getFileHash() {
		return fileHash;
	}

	/** UTF-8 hex SHA-1 the client already holds; non-null only on conditional document requests. */
	public byte[] getExpectedSha1() {
		return expectedSha1;
	}

	public long getOffset() {
		return offset;
	}

	/** Inclusive end offset of a ranged request; null means to the end of the file. */
	public Long getEndInclusive() {
		return endInclusive;
	}
}
