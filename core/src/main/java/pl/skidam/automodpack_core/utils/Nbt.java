package pl.skidam.automodpack_core.utils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uncompressed Java NBT used by vanilla files such as servers.dat. */
final class Nbt {
	static final byte END = 0;
	static final byte BYTE = 1;
	static final byte SHORT = 2;
	static final byte INT = 3;
	static final byte LONG = 4;
	static final byte FLOAT = 5;
	static final byte DOUBLE = 6;
	static final byte BYTE_ARRAY = 7;
	static final byte STRING = 8;
	static final byte LIST = 9;
	static final byte COMPOUND = 10;
	static final byte INT_ARRAY = 11;
	static final byte LONG_ARRAY = 12;

	private Nbt() {}

	static Compound readNamedCompound(DataInput in) throws IOException {
		byte type = in.readByte();
		if (type == END) return new Compound();
		if (type != COMPOUND) throw new IOException("Expected an NBT compound, got type " + type);
		in.readUTF();
		return readCompound(in);
	}

	static void writeNamedCompound(DataOutput out, Compound compound) throws IOException {
		out.writeByte(COMPOUND);
		out.writeUTF("");
		writeCompound(out, compound);
	}

	private static Compound readCompound(DataInput in) throws IOException {
		Compound compound = new Compound();
		while (true) {
			byte type = in.readByte();
			if (type == END) return compound;
			String name = in.readUTF();
			compound.put(name, readPayload(in, type));
		}
	}

	private static void writeCompound(DataOutput out, Compound compound) throws IOException {
		for (Map.Entry<String, Object> entry : compound.entries.entrySet()) {
			byte type = typeOf(entry.getValue());
			out.writeByte(type);
			out.writeUTF(entry.getKey());
			writePayload(out, entry.getValue());
		}
		out.writeByte(END);
	}

	private static Object readPayload(DataInput in, byte type) throws IOException {
		return switch (type) {
			case BYTE -> in.readByte();
			case SHORT -> in.readShort();
			case INT -> in.readInt();
			case LONG -> in.readLong();
			case FLOAT -> in.readFloat();
			case DOUBLE -> in.readDouble();
			case BYTE_ARRAY -> {
				int length = in.readInt();
				if (length < 0) throw new IOException("Negative NBT byte array length");
				byte[] bytes = new byte[length];
				in.readFully(bytes);
				yield bytes;
			}
			case STRING -> in.readUTF();
			case LIST -> {
				byte elementType = in.readByte();
				int count = in.readInt();
				if (count < 0) throw new IOException("Negative NBT list length");
				List<Object> values = new ArrayList<>(count);
				for (int i = 0; i < count; i++) values.add(readPayload(in, elementType));
				yield new TagList(elementType, values);
			}
			case COMPOUND -> readCompound(in);
			case INT_ARRAY -> {
				int length = in.readInt();
				if (length < 0) throw new IOException("Negative NBT int array length");
				int[] values = new int[length];
				for (int i = 0; i < length; i++) values[i] = in.readInt();
				yield values;
			}
			case LONG_ARRAY -> {
				int length = in.readInt();
				if (length < 0) throw new IOException("Negative NBT long array length");
				long[] values = new long[length];
				for (int i = 0; i < length; i++) values[i] = in.readLong();
				yield values;
			}
			default -> throw new IOException("Unknown NBT type " + type);
		};
	}

	private static void writePayload(DataOutput out, Object value) throws IOException {
		if (value instanceof Byte v) out.writeByte(v);
		else if (value instanceof Short v) out.writeShort(v);
		else if (value instanceof Integer v) out.writeInt(v);
		else if (value instanceof Long v) out.writeLong(v);
		else if (value instanceof Float v) out.writeFloat(v);
		else if (value instanceof Double v) out.writeDouble(v);
		else if (value instanceof byte[] v) {
			out.writeInt(v.length);
			out.write(v);
		} else if (value instanceof String v) out.writeUTF(v);
		else if (value instanceof TagList v) {
			out.writeByte(v.elementType);
			out.writeInt(v.values.size());
			for (Object element : v.values) writePayload(out, element);
		} else if (value instanceof Compound v) writeCompound(out, v);
		else if (value instanceof int[] v) {
			out.writeInt(v.length);
			for (int element : v) out.writeInt(element);
		} else if (value instanceof long[] v) {
			out.writeInt(v.length);
			for (long element : v) out.writeLong(element);
		} else throw new IOException("Unsupported NBT value " + value.getClass().getName());
	}

	private static byte typeOf(Object value) throws IOException {
		if (value instanceof Byte) return BYTE;
		if (value instanceof Short) return SHORT;
		if (value instanceof Integer) return INT;
		if (value instanceof Long) return LONG;
		if (value instanceof Float) return FLOAT;
		if (value instanceof Double) return DOUBLE;
		if (value instanceof byte[]) return BYTE_ARRAY;
		if (value instanceof String) return STRING;
		if (value instanceof TagList) return LIST;
		if (value instanceof Compound) return COMPOUND;
		if (value instanceof int[]) return INT_ARRAY;
		if (value instanceof long[]) return LONG_ARRAY;
		throw new IOException("Unsupported NBT value " + value.getClass().getName());
	}

	static final class Compound {
		final Map<String, Object> entries = new LinkedHashMap<>();

		Object get(String name) {
			return entries.get(name);
		}

		void put(String name, Object value) {
			entries.put(name, value);
		}
	}

	static final class TagList {
		final byte elementType;
		final List<Object> values;

		TagList(byte elementType, List<Object> values) {
			this.elementType = elementType;
			this.values = values;
		}
	}
}
