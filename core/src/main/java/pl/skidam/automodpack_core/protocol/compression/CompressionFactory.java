package pl.skidam.automodpack_core.protocol.compression;

import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;
import io.airlift.compress.lzo.LzoCompressor;
import io.airlift.compress.lzo.LzoDecompressor;
import io.airlift.compress.snappy.SnappyCompressor;
import io.airlift.compress.snappy.SnappyDecompressor;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;

public class CompressionFactory {

	public static CompressionCodec createCodec(CompressionType compressionType) {
		return switch (compressionType) {
			case NONE -> new NoneCompression();
			case GZIP -> new GzipCompression();
			case ZSTD -> new AirliftCompressionCodec(CompressionType.ZSTD, new ZstdCompressor(), new ZstdDecompressor());
			case SNAPPY -> new AirliftCompressionCodec(CompressionType.SNAPPY, new SnappyCompressor(), new SnappyDecompressor());
			case LZ4 -> new AirliftCompressionCodec(CompressionType.LZ4, new Lz4Compressor(), new Lz4Decompressor());
			case LZO -> new AirliftCompressionCodec(CompressionType.LZO, new LzoCompressor(), new LzoDecompressor());
		};
	}

	public static boolean isAvailable(CompressionType compressionType) {
		try {
			createCodec(compressionType);
			return true;
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}
}
