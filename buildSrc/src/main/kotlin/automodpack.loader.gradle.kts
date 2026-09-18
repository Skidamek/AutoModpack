import org.gradle.api.plugins.BasePluginExtension

// The identity and compiler encoding every loader module shares: one archivesName/version/group
// formula (<mod.id>-<module name>) and one UTF-8 compile setting, instead of a copy per loader
// script. Java level and toolchains stay per script - the neoforge modules cross-compile (see
// automodpack.neoforge-toolchain), the rest pin 17.
configure<BasePluginExtension> {
	archivesName = project.property("mod.id") as String + "-" + project.name
	version = project.property("mod_version") as String
	group = project.property("mod.group") as String
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
