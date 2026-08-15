package pl.skidam.automodpack_core.update;

import java.lang.reflect.Type;
import java.util.Set;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.PreservationProof;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

/** Explicit construction adapters for immutable plan records on Minecraft's Gson 2.8.x runtime. */
public final class UpdatePlanJsonAdapters {
	private static final Type STRING_SET = new TypeToken<Set<String>>() {
	}.getType();

	private UpdatePlanJsonAdapters() {}

	public static final class OperationAdapter implements JsonDeserializer<Operation> {
		@Override
		public Operation deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = object(json, "operation");
			return new Operation(required(object, "root", Root.class, context), string(object, "relativePath"), required(object, "operation", OperationType.class, context),
					string(object, "expectedObjectHash"), number(object, "expectedSize"), string(object, "expectedExistingHash"));
		}
	}

	public static final class ProjectedFileAdapter implements JsonDeserializer<ProjectedFile> {
		@Override
		public ProjectedFile deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = object(json, "projected file");
			return new ProjectedFile(required(object, "root", Root.class, context), string(object, "relativePath"), bool(object, "present"), string(object, "expectedHash"),
					number(object, "expectedSize"));
		}
	}

	public static final class PreservationAdapter implements JsonDeserializer<Preservation> {
		@Override
		public Preservation deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = object(json, "preservation");
			return new Preservation(required(object, "root", Root.class, context), string(object, "relativePath"), string(object, "expectedHash"), number(object, "expectedSize"),
					required(object, "proof", PreservationProof.class, context));
		}
	}

	public static final class BaselineCaptureAdapter implements JsonDeserializer<BaselineCapture> {
		@Override
		public BaselineCapture deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = object(json, "baseline capture");
			return new BaselineCapture(required(object, "root", Root.class, context), string(object, "relativePath"), string(object, "expectedHash"), number(object, "expectedSize"),
					bool(object, "absent"));
		}
	}

	public static final class ConflictAdapter implements JsonDeserializer<Conflict> {
		@Override
		public Conflict deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = object(json, "conflict");
			Set<String> modIds = context.deserialize(require(object, "modIds"), STRING_SET);
			return new Conflict(string(object, "modpackId"), string(object, "conflictId"), modIds, string(object, "sourcePath"), string(object, "sourceHash"), number(object, "sourceSize"),
					string(object, "targetPath"), string(object, "targetHash"), number(object, "targetSize"), required(object, "action", ConflictAction.class, context));
		}
	}

	private static JsonObject object(JsonElement json, String description) {
		if (json == null || !json.isJsonObject()) throw new JsonParseException("Update-plan " + description + " must be an object");
		return json.getAsJsonObject();
	}

	private static JsonElement require(JsonObject object, String name) {
		JsonElement value = object.get(name);
		if (value == null || value.isJsonNull()) throw new JsonParseException("Update-plan field is missing: " + name);
		return value;
	}

	private static <T> T required(JsonObject object, String name, Class<T> type, JsonDeserializationContext context) {
		T value = context.deserialize(require(object, name), type);
		if (value == null) throw new JsonParseException("Update-plan field is null: " + name);
		return value;
	}

	private static String string(JsonObject object, String name) {
		JsonElement value = object.get(name);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static long number(JsonObject object, String name) {
		return require(object, name).getAsLong();
	}

	private static boolean bool(JsonObject object, String name) {
		return require(object, name).getAsBoolean();
	}
}
