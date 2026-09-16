// Rewrites banned fully qualified names in Java sources into imports and finds the remaining
// ones. Consumed by the fixFullyQualifiedNames / checkNoFullyQualifiedNames tasks in the
// stonecutter controller script; lives here so the masking lexer and rewrite rules are unit-testable.

internal val fqnFixPattern = Regex("""(?<![\w$."])(pl|java|javax)(?:\.[a-z][\w]*)*\.([A-Z][\w]*)\b""")
internal val fqnAnyPattern = Regex("""(?<![\w$."])(?:[a-z][\w]*\.){2,}[A-Z][\w]*\b""")
internal val javaPackagePattern = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
internal val javaImportPattern = Regex("""(?m)^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;""")
internal val javaTypeDeclPattern = Regex("""\b(?:class|interface|enum|record)\s+([A-Z][\w]*)""")

internal fun maskJavaNoise(content: String): String {
	val masked = StringBuilder(content.length)
	var i = 0
	var blockComment = false
	var lineComment = false
	var stringChar: Char? = null
	var textBlock = false
	while (i < content.length) {
		val c = content[i]
		val next = if (i + 1 < content.length) content[i + 1] else '\u0000'
		val next2 = if (i + 2 < content.length) content[i + 2] else '\u0000'
		when {
			lineComment -> {
				if (c == '\n') {
					lineComment = false
					masked.append(c)
				} else {
					masked.append(' ')
				}
				i++
			}

			blockComment -> {
				if (c == '*' && next == '/') {
					blockComment = false
					masked.append("  ")
					i += 2
				} else {
					masked.append(if (c == '\n') '\n' else ' ')
					i++
				}
			}

			textBlock -> {
				if (c == '"' && next == '"' && next2 == '"') {
					textBlock = false
					masked.append("   ")
					i += 3
				} else {
					masked.append(if (c == '\n') '\n' else ' ')
					i++
				}
			}

			stringChar != null -> {
				if (c == '\\') {
					masked.append("  ")
					i += 2
				} else if (c == stringChar) {
					stringChar = null
					masked.append(' ')
					i++
				} else {
					masked.append(if (c == '\n') '\n' else ' ')
					i++
				}
			}

			c == '/' && next == '/' -> {
				lineComment = true
				masked.append("  ")
				i += 2
			}

			c == '/' && next == '*' -> {
				blockComment = true
				masked.append("  ")
				i += 2
			}

			c == '"' && next == '"' && next2 == '"' -> {
				textBlock = true
				masked.append("   ")
				i += 3
			}

			c == '"' || c == '\'' -> {
				stringChar = c
				masked.append(' ')
				i++
			}

			else -> {
				masked.append(c)
				i++
			}
		}
	}
	return masked.toString()
}

internal fun isImportOrPackageLine(content: String, offset: Int): Boolean {
	val lineStart = content.lastIndexOf('\n', offset) + 1
	val lineEnd = content.indexOf('\n', offset).let { if (it == -1) content.length else it }
	val line = content.substring(lineStart, lineEnd).trim()
	return line.startsWith("import ") || line.startsWith("package ")
}

fun fixFullyQualifiedNames(content: String): String {
	val masked = maskJavaNoise(content)
	val candidates = fqnFixPattern.findAll(masked).filter { !isImportOrPackageLine(content, it.range.first) }.toList()
	if (candidates.isEmpty()) return content
	val filePackage = javaPackagePattern.find(content)?.groupValues?.get(1).orEmpty()
	val imports = javaImportPattern.findAll(content).map { it.groupValues[2] }.toSet()
	val declaredTypes = javaTypeDeclPattern.findAll(masked).map { it.groupValues[1] }.toSet()
	val fixes =
		candidates.map { Regex("""\.([A-Z][\w]*)$""").find(it.value)!!.groupValues[1] to it.value }.distinct().filter { (simple, fqn) ->
			val pkg = fqn.substring(0, fqn.length - simple.length - 1)
			when {
				pkg == "java.lang" || pkg == filePackage -> true
				imports.contains(fqn) -> true
				imports.any { it.substringAfterLast('.') == simple } || simple in declaredTypes -> false
				else -> true
			}
		}
	if (fixes.isEmpty()) return content
	val ranges = candidates.filter { match -> fixes.any { it.second == match.value } }.sortedByDescending { it.range.first }
	val fixed = StringBuilder(content)
	for (match in ranges) fixed.replace(match.range.first, match.range.last + 1, fixes.first { it.second == match.value }.first)
	val neededImports =
		fixes
			.map { (simple, fqn) -> fqn.substring(0, fqn.length - simple.length - 1) + "." + simple }
			.filter { fqn ->
				val pkg = fqn.substringBeforeLast('.')
				pkg != "java.lang" && pkg != filePackage && !imports.contains(fqn)
			}.distinct()
	if (neededImports.isEmpty()) return fixed.toString()
	val lastImport = javaImportPattern.findAll(fixed).lastOrNull()
	if (lastImport != null) {
		val insertAt = fixed.indexOf('\n', lastImport.range.last).let { if (it == -1) fixed.length else it }
		fixed.insert(insertAt, neededImports.joinToString("") { "\nimport $it;" })
	} else {
		val pkg = javaPackagePattern.find(fixed)
		if (pkg != null) {
			val insertAt = fixed.indexOf('\n', pkg.range.last).let { if (it == -1) fixed.length else it }
			fixed.insert(insertAt, "\n" + neededImports.joinToString("") { "\nimport $it;" })
		} else {
			fixed.insert(0, neededImports.joinToString("") { "import $it;\n" } + "\n")
		}
	}
	return fixed.toString()
}

fun findFullyQualifiedNames(content: String): List<String> {
	val masked = maskJavaNoise(content)
	return fqnAnyPattern
		.findAll(masked)
		.mapNotNull { match ->
			if (isImportOrPackageLine(content, match.range.first)) return@mapNotNull null
			val lineNo = content.substring(0, match.range.first).count { it == '\n' } + 1
			"$lineNo: ${match.value}"
		}.toList()
}
