package pl.skidam.automodpack_core.modpack.host;

import java.util.Objects;

import pl.skidam.automodpack_core.modpack.PackAbsence;

/** What one process serves to joining players: its own generated pack, an installed pack the player shared, or nothing. */
public sealed interface HostSource {
	record Generated() implements HostSource {}

	record Relay(String modpackId) implements HostSource {
		public Relay {
			Objects.requireNonNull(modpackId, "modpack ID");
		}
	}

	record None(PackAbsence absence) implements HostSource {
		public None {
			Objects.requireNonNull(absence, "absence");
		}
	}
}
