import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.plugins.JavaPluginExtension

// NeoForge 21.x artifacts resolve only against a Java 21 consumer, 26.x against 25; ask for those
// variants explicitly instead of inheriting a release-driven Java 17 consumer attribute. The
// universal outer jar must load on Java 17 (1.18.2): compile on the matching toolchain, emit Java 17
// bytecode and API usage.
val resolveJvm = if (loaderVersion().startsWith("26.")) 25 else 21
configurations.configureEach {
	if (isCanBeResolved) attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, resolveJvm)
}

configure<JavaPluginExtension> {
	toolchain.languageVersion.set(JavaLanguageVersion.of(resolveJvm))
}

tasks.withType<JavaCompile> {
	options.release.set(17)
}
