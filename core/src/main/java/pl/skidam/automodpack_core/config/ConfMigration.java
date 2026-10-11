package pl.skidam.automodpack_core.config;

import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;

import pl.skidam.reconf.ConfigPath;
import pl.skidam.reconf.Document;

/**
 * One config type's extension of the generic human-config store: the repair step for keys a newer model replaced, and the
 * nested declaration shapes whose documented members the store materializes. The repair runs twice over the same rule:
 * reading hands the model parse a tree the current shape understands, and saving applies the very same rule to the
 * document so the file converges on the next write. Reading never writes - a read-only config directory must still load
 * - so a repair that could not be persisted is never the load's problem. Owning the nested shapes is what keeps the
 * store free of what any one config type means.
 */
public interface ConfMigration {
	/**
	 * Every extension the human-config store applies, in order; each entry owns its own config type. A method, not a
	 * field: a constant here would be initialized before the implementor it names ever gets its own instance, and the
	 * registry would hold null.
	 */
	static List<ConfMigration> registry() {
		return List.of(GroupSelectionMigration.INSTANCE, ServerSideExclusionMigration.INSTANCE);
	}

	/** The extensions that own this config type; the store iterates them and special-cases nothing. */
	static List<ConfMigration> applicableTo(Class<?> type) {
		return registry().stream().filter(migration -> migration.appliesTo(type)).toList();
	}

	boolean appliesTo(Class<?> type);

	/** Translates every replaced key of the document's tree into the shape the current model understands. */
	void forRead(JsonObject document);

	/** Applies the same translation to the document itself, so the file carries it into the next save. */
	void repair(Document document, Path path);

	/** One object the model nests under a dynamic path, the ones the store cannot reach from the config type alone. */
	record Shape(ConfigPath path, Object value) {}

	/** Every nested declaration of this config type's model; the store documents each one's declared fields. */
	default List<Shape> nestedShapes(Object model) {
		return List.of();
	}
}
