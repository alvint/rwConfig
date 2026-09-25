// compatibility { } below comes with plugin-publish, from Gradle's
// compatibility-plugin, which writes it into the plugin descriptor
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    signing
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "net.rabbitware.config"
// The version follows the library's, which the Maven build beside this one
// owns. Reading it from the root pom means `mvn versions:set` moves this plugin
// too, and there is nothing to keep in step by hand.
version = Regex("""<artifactId>parent</artifactId>\s*<version>([^<]+)</version>""")
    .find(file("../pom.xml").readText())
    ?.groupValues?.get(1)
    ?: error("could not read the version from ../pom.xml")

repositories {
    mavenCentral()
}

// Gradle 9 runs on Java 17, so the plugin itself has to load there. The
// analyzer needs 21 and runs in a JVM of its own, from the project's toolchain.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

// The analyzer version a project gets by default is the plugin's own.
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("**/version.properties") {
        expand("version" to pluginVersion)
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("5.12.2")
        }
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter("5.12.2")
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                    // the builds under test resolve the analyzer and the library
                    // from the local Maven repository, where `mvn install` puts them
                    val version = project.version.toString()
                    val local = file("${System.getProperty("user.home")}/.m2/repository/net/rabbitware/config")
                    systemProperty("rwconfig.version", version)
                    // a Java 21 for the toolchain test to find, and for the
                    // oldest Gradle supported, which cannot run on anything newer
                    val java21 = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
                    jvmArgumentProviders.add(CommandLineArgumentProvider {
                        listOf("-Drwconfig.java21.home=" + java21.get().metadata.installationPath.asFile.absolutePath)
                    })
                    doFirst {
                        for (module in listOf("rwconfig-analyzer", "config", "plugin-api")) {
                            val jar = local.resolve("$module/$version/$module-$version.jar")
                            check(jar.isFile) { "$jar is missing - run `mvn install` in the parent directory first" }
                        }
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}

gradlePlugin {
    website = "https://github.com/alvint/rwConfig"
    vcsUrl = "https://github.com/alvint/rwConfig"
    testSourceSets(sourceSets["functionalTest"])
    plugins {
        create("rwconfig") {
            id = "net.rabbitware.rwconfig"
            implementationClass = "net.rabbitware.config.gradle.RwconfigPlugin"
            displayName = "rwConfig"
            description = "Checks a project's Java sources against its rwconfig file, so that a " +
                "misread property fails the build rather than the application."
            tags = listOf("configuration", "config", "validation", "static-analysis", "rwconfig")
            // every functional test runs with the configuration cache on
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

// The Plugin Portal does not require signatures, but a build that turns on
// Gradle's dependency verification can only check a signed plugin against its
// author's key. plugin-publish signs whatever the signing plugin is set up to
// sign, here with the same gpg and gpg-agent the Maven release uses.
signing {
    useGpgCmd()
}
