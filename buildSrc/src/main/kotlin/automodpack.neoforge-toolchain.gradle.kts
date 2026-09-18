import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.plugins.JavaPluginExtension

// NeoForge 21.x artifacts resolve only against a Java 21 consumer; ask for those variants
// explicitly instead of inheriting a release-driven Java 17 consumer attribute. But the universal
// outer jar must load on Java 17 (1.18.2): compile on the 21 toolchain, emit Java 17 bytecode and
// API usage.
configurations.configureEach {
	if (isCanBeResolved) attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

configure<JavaPluginExtension> {
	toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
	options.release.set(17)
}
