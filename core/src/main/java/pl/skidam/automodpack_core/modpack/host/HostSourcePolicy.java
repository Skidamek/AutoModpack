package pl.skidam.automodpack_core.modpack.host;

import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.modpack.PackAbsence;

/**
 * The one decision of what a process hosts. A server always serves its own generation. A client that runs an
 * installed pack serves it only after its player shared it, and otherwise serves nothing: generating from a tree
 * another pack manages would publish that pack's files as someone else's. A client without one generates.
 */
public final class HostSourcePolicy {
	private HostSourcePolicy() {}

	public static HostSource select(EnvironmentType environment, String activeModpackId, boolean activePackShared) {
		if (environment != EnvironmentType.CLIENT) return new HostSource.Generated();
		if (activeModpackId == null || activeModpackId.isBlank()) return new HostSource.Generated();
		if (activePackShared) return new HostSource.Relay(activeModpackId);
		return new HostSource.None(new PackAbsence(PackAbsence.Kind.NOT_SHARED, "This instance runs modpack " + activeModpackId
				+ ", which is not shared; turn on LAN sharing on its page to host it to joining players"));
	}
}
