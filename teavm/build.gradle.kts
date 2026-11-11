plugins {
	id("java")
	id("org.teavm") version "0.15.0"
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	maven("https://libraries.minecraft.net")
}

// Classes that patch holes in teavm-classlib (java.util.UUID, j.u.c.locks,
// CompletableFuture, ...). They must be merged INTO the classlib jar because
// the TeaVM toolchain loads the classlib from its parent classloader — a
// plain project classpath entry can never override it.
sourceSets {
	create("classlibOverrides")
	create("unsafeStub")
}

val teavmVersion = "0.15.0"

val vanillaClasslib: Configuration by configurations.creating {
	isTransitive = false
}

dependencies {
	implementation(rootProject)
	vanillaClasslib("org.teavm:teavm-classlib:$teavmVersion")
	// Patched classlib classes are copied from teavm-classlib sources and
	// compile against TeaVM internals.
	"classlibOverridesCompileOnly"("org.teavm:teavm-classlib:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-platform:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-jso:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-jso-apis:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-jso-impl:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-interop:$teavmVersion")
	"classlibOverridesCompileOnly"("org.teavm:teavm-core:$teavmVersion")
}

// The sun.misc.Unsafe stub is a split package under JPMS; compile it alone
// with pre-module rules and merge it into the patched classlib.
tasks.named<JavaCompile>("compileUnsafeStubJava") {
	options.release = 8
	options.compilerArgs.add("-nowarn")
}

val patchedClasslib = tasks.register<Jar>("patchedClasslib") {
	archiveBaseName = "teavm-classlib-patched"
	from(sourceSets["classlibOverrides"].output)
	from(sourceSets["unsafeStub"].output)
	from(provider { zipTree(vanillaClasslib.singleFile) })
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	exclude("META-INF/*.SF", "META-INF/*.RSA")
}

dependencies {
	"teavmClasspath"(files(patchedClasslib.map { it.archiveFile }))
	"teavmClasspath"("org.teavm:teavm-tooling:$teavmVersion") {
		exclude(group = "org.teavm", module = "teavm-classlib")
	}
}

teavm.js {
	// Stage C: full client entry (pulls the WebGL/GLFW render path). Milestone-1
	// data-layer smoke entry was net.lax1dude.eaglercraft.v1_21.boot.EaglerBootMain.
	mainClass = "net.lax1dude.eaglercraft.v1_21.boot.EaglerClientMain"
	targetFileName = "classes.js"
	sourceMap = true
	obfuscated = false
}

// The default task classpath does not put this project's classes ahead of
// dependency jars, so same-name overrides (netty InternalLoggerFactory,
// PlatformExecutors) silently lose to the originals. Order explicitly:
// our classes, then the patched classlib, then everything else.
tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
	classpath.setFrom(
		sourceSets["main"].output,
		files(patchedClasslib.map { it.archiveFile }),
		configurations["teavmClasspath"],
		sourceSets["main"].runtimeClasspath
	)
}

tasks.register<Sync>("bundleBrowserClient") {
	group = "minecraft"
	description = "Build and stage the Minecraft 1.21.11 JavaScript browser client"
	dependsOn("generateJavaScript")
	from(layout.buildDirectory.dir("generated/teavm/js"))
	from("run") {
		include("index.html", "vanilla-assets.zip")
	}
	into(layout.buildDirectory.dir("browser-client"))
}
