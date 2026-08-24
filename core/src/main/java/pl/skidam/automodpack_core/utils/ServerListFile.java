package pl.skidam.automodpack_core.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Vanilla `servers.dat` in the game directory. Written at bootstrap import so Minecraft loads the entry on the same launch. */
public final class ServerListFile {
	private ServerListFile() {}

	public static void upsert(Path file, String name, String address) throws IOException {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Server list name is required");
		if (address == null || address.isBlank()) throw new IllegalArgumentException("Server list address is required");
		String normalizedAddress = AddressHelpers.formatAddress(AddressHelpers.parseOrigin(address));
		Nbt.Compound root = readRoot(file);
		Nbt.TagList servers = servers(root);
		for (Object value : servers.values) {
			if (!(value instanceof Nbt.Compound entry)) continue;
			Object ip = entry.get("ip");
			if (!(ip instanceof String stored) || !sameAddress(stored, normalizedAddress)) continue;
			Object currentName = entry.get("name");
			if (!(currentName instanceof String existingName) || existingName.isBlank()) entry.put("name", name);
			writeRoot(file, root);
			return;
		}
		Nbt.Compound added = new Nbt.Compound();
		added.put("name", name);
		added.put("ip", normalizedAddress);
		servers.values.add(added);
		root.put("servers", new Nbt.TagList(Nbt.COMPOUND, servers.values));
		writeRoot(file, root);
	}

	public static List<Entry> read(Path file) throws IOException {
		List<Entry> entries = new ArrayList<>();
		for (Object value : servers(readRoot(file)).values) {
			if (!(value instanceof Nbt.Compound entry)) continue;
			Object name = entry.get("name");
			Object ip = entry.get("ip");
			entries.add(new Entry(name instanceof String text ? text : "", ip instanceof String text ? text : ""));
		}
		return List.copyOf(entries);
	}

	private static Nbt.Compound readRoot(Path file) throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return emptyRoot();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("servers.dat is not a regular file: " + file);
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			return Nbt.readNamedCompound(in);
		}
	}

	private static void writeRoot(Path file, Nbt.Compound root) throws IOException {
		Path parent = file.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("servers.dat path has no parent: " + file);
		Files.createDirectories(parent);
		Path temporary = parent.resolve("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
				Nbt.writeNamedCompound(out, root);
				out.flush();
			}
			try {
				DurableFiles.replace(temporary, file);
			} catch (AtomicMoveNotSupportedException e) {
				throw new IOException("The filesystem cannot durably replace " + file + "; use a major local filesystem with atomic rename support", e);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static Nbt.Compound emptyRoot() {
		Nbt.Compound root = new Nbt.Compound();
		root.put("servers", new Nbt.TagList(Nbt.COMPOUND, new ArrayList<>()));
		return root;
	}

	private static Nbt.TagList servers(Nbt.Compound root) throws IOException {
		Object value = root.get("servers");
		if (value == null) {
			Nbt.TagList created = new Nbt.TagList(Nbt.COMPOUND, new ArrayList<>());
			root.put("servers", created);
			return created;
		}
		if (!(value instanceof Nbt.TagList list) || (list.elementType != Nbt.COMPOUND && !(list.elementType == Nbt.END && list.values.isEmpty())))
			throw new IOException("servers.dat does not contain a compound list named servers");
		if (list.elementType == Nbt.END) {
			Nbt.TagList created = new Nbt.TagList(Nbt.COMPOUND, new ArrayList<>());
			root.put("servers", created);
			return created;
		}
		return list;
	}

	private static boolean sameAddress(String stored, String wanted) {
		try {
			return AddressHelpers.formatAddress(AddressHelpers.parseOrigin(stored)).equals(wanted);
		} catch (IllegalArgumentException e) {
			return stored.equalsIgnoreCase(wanted);
		}
	}

	public record Entry(String name, String ip) {}
}
