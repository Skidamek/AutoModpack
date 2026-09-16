import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FqnImportsTest {
	@Test
	fun `masking hides fqn-looking text inside comments strings and text blocks`() {
		val lineCommented = "// java.util.List\njava.util.Map m;\n"
		val lineMasked = maskJavaNoise(lineCommented)
		assertEquals(lineCommented.length, lineMasked.length)
		assertFalse(lineMasked.contains("java.util.List"))
		assertTrue(lineMasked.contains("java.util.Map m;"))

		val blockCommented = "/* java.util.List */\njava.util.Map m;\n"
		val blockMasked = maskJavaNoise(blockCommented)
		assertEquals(blockCommented.length, blockMasked.length)
		assertFalse(blockMasked.contains("java.util.List"))
		assertTrue(blockMasked.contains("java.util.Map m;"))

		val escaped = "\"java.util.ArrayList \\\" x\" java.util.Map m;"
		val escapedMasked = maskJavaNoise(escaped)
		assertEquals(escaped.length, escapedMasked.length)
		assertFalse(escapedMasked.contains("java.util.ArrayList"))
		assertTrue(escapedMasked.contains("java.util.Map m;"))

		val textBlock = "\"\"\"\njava.util.TreeSet // still inside\n\"\"\";\njava.util.Map m;\n"
		val textBlockMasked = maskJavaNoise(textBlock)
		assertEquals(textBlock.length, textBlockMasked.length)
		assertFalse(textBlockMasked.contains("java.util.TreeSet"))
		assertTrue(textBlockMasked.contains("java.util.Map m;"))
	}

	@Test
	fun `find reports 1-based line numbers and skips masked and import lines`() {
		val content = """
package p;

import com.example.Foo;

class C {
	java.util.List list;
	String s = "com.example.Bar";
	// com.example.Baz
	org.apache.commons.io.FileUtils utils;
}
""".trim() + "\n"
		assertEquals(listOf("6: java.util.List", "9: org.apache.commons.io.FileUtils"), findFullyQualifiedNames(content))
	}

	@Test
	fun `fix rewrites fqns covered by existing imports without adding them twice`() {
		val content = """
package pl.skidam.test;

import java.util.ArrayList;

class A {
	java.util.ArrayList list = new java.util.ArrayList();
}
""".trim() + "\n"
		val expected = """
package pl.skidam.test;

import java.util.ArrayList;

class A {
	ArrayList list = new ArrayList();
}
""".trim() + "\n"
		assertEquals(expected, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix skips fqns whose simple name collides with an import or a declared type`() {
		val content = """
package pl.skidam.test;

import com.example.ArrayList;

class Locale {}

class A {
	java.util.ArrayList list;
	java.util.Locale locale;
}
""".trim() + "\n"
		assertEquals(content, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix rewrites java lang and same package fqns without adding imports`() {
		val content = """
package pl.skidam.test;

class A {
	java.lang.String name;
	pl.skidam.test.Helper helper;
}

class Helper {}
""".trim() + "\n"
		val expected = """
package pl.skidam.test;

class A {
	String name;
	Helper helper;
}

class Helper {}
""".trim() + "\n"
		assertEquals(expected, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix rewrites fqn and inserts import after the last import`() {
		val content = """
package pl.skidam.test;

import java.util.Map;

class A {
	java.util.List list;
}
""".trim() + "\n"
		val expected = """
package pl.skidam.test;

import java.util.Map;
import java.util.List;

class A {
	List list;
}
""".trim() + "\n"
		assertEquals(expected, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix inserts import after the package statement when there are no imports`() {
		val content = """
package pl.skidam.test;

class A {
	java.util.List list;
}
""".trim() + "\n"
		val expected = """
package pl.skidam.test;

import java.util.List;

class A {
	List list;
}
""".trim() + "\n"
		assertEquals(expected, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix inserts import at the top when there is no package or imports`() {
		val content = """
class A {
	java.util.List list;
}
""".trim() + "\n"
		val expected = """
import java.util.List;

class A {
	List list;
}
""".trim() + "\n"
		assertEquals(expected, fixFullyQualifiedNames(content))
	}

	@Test
	fun `fix is idempotent`() {
		val content = """
package pl.skidam.test;

import java.util.Map;

class A {
	java.util.List list = new java.util.ArrayList();
	java.lang.String name;
}
""".trim() + "\n"
		val once = fixFullyQualifiedNames(content)
		assertNotEquals(content, once)
		assertEquals(once, fixFullyQualifiedNames(once))
	}
}
