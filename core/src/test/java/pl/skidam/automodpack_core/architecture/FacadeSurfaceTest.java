package pl.skidam.automodpack_core.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Budget on the public surface of the modules named in CONTEXT.md. A deep module hides its implementation behind a narrow door, so the door may shrink but never widen by accident: a member added to one of these types
 * fails the build until the budget is raised on purpose.
 * The budget counts public declared methods, constructors and fields; nested types and synthetic members do not count.
 */
class FacadeSurfaceTest {
	private static final String BASELINE = "/architecture/facades.txt";
	private static final String NEWLINE = "\n";

	@Test
	void facadesStayWithinTheirDeclaredSurface() throws IOException {
		Map<String, Integer> budgets = budgets();
		if (budgets.isEmpty()) fail("Baseline " + BASELINE + " declares no facades");
		List<String> violations = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : budgets.entrySet()) {
			String name = entry.getKey();
			int budget = entry.getValue();
			int actual = surface(load(name));
			if (actual > budget) violations.add(name + " has " + actual + " public members but the budget is " + budget);
		}
		if (violations.isEmpty()) return;
		fail("Module doors widened: " + String.join("; ", violations) + NEWLINE + "If the new member is part of the module interface, raise its budget in core/src/test/resources" + BASELINE
				+ "; if it is an implementation detail, make it private.");
	}

	private static Class<?> load(String name) {
		try {
			return Class.forName(name, false, FacadeSurfaceTest.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("CONTEXT.md names a module that no longer exists: " + name, e);
		}
	}

	private static int surface(Class<?> type) {
		int members = 0;
		for (Method method : type.getDeclaredMethods()) if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) members++;
		for (Constructor<?> constructor : type.getDeclaredConstructors()) if (Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic()) members++;
		for (Field field : type.getDeclaredFields()) if (Modifier.isPublic(field.getModifiers()) && !field.isSynthetic()) members++;
		return members;
	}

	private static Map<String, Integer> budgets() throws IOException {
		try (InputStream stream = FacadeSurfaceTest.class.getResourceAsStream(BASELINE)) {
			if (stream == null) throw new IllegalStateException("Missing baseline resource " + BASELINE);
			Map<String, Integer> budgets = new LinkedHashMap<>();
			for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).collect(Collectors.toList())) {
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] parts = line.split("\\s+");
				if (parts.length != 2) throw new IllegalStateException("Malformed facade baseline line: " + line);
				budgets.put(parts[0], Integer.parseInt(parts[1]));
			}
			return budgets;
		}
	}
}
