package net.rabbitware.config.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Checks a project's Java sources against its {@code rwconfig} file, as the
 * Maven plugin does: a misspelled property name, or the wrong getter for its
 * declared type, fails the build rather than the application.
 *
 * <p>Adds an {@code rwconfigCheck} task to any project with the {@code java}
 * plugin, run before {@code compileJava} and as part of {@code check}. Only the
 * sources are read, so there is no reason to compile first - and a misread
 * property is more useful reported before the compiler has spent time on the
 * module than after. A project with no {@code rwconfig} file is left alone, so
 * the plugin is harmless to apply to every project in a build.
 */
public class RwconfigPlugin implements Plugin<Project> {

    /** The analyzer version a project gets unless it asks for another: this plugin's own. */
    static final String VERSION = loadVersion();

    /** Created by Gradle when the plugin is applied. */
    public RwconfigPlugin() {
    }

    @Override
    public void apply(Project project) {
        ProviderFactory providers = project.getProviders();
        RwconfigExtension extension = project.getExtensions().create("rwconfig", RwconfigExtension.class);
        extension.getFailOnError().convention(flag(providers, "rwconfig.failOnError", true));
        extension.getReportUnread().convention(flag(providers, "rwconfig.reportUnread", true));
        extension.getSkip().convention(flag(providers, "rwconfig.skip", false));
        // ArrayLists, not List.of or Stream.toList - Gradle 8's configuration
        // cache cannot read an immutable JDK list back
        extension.getSkipRules().convention(providers.gradleProperty("rwconfig.skipRules")
            .map(rules -> Arrays.stream(rules.split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new)))
            .orElse(new ArrayList<>()));

        // Resolved from the project's own repositories, the way the checkstyle
        // and pmd configurations are - and replaceable the same way.
        Configuration analyzer = project.getConfigurations().create("rwconfig", configuration -> {
            configuration.setDescription("The rwConfig analyzer that rwconfigCheck runs.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.defaultDependencies(dependencies -> dependencies.add(
                project.getDependencies().create("net.rabbitware.config:rwconfig-analyzer:" + VERSION)));
        });

        project.getPluginManager().withPlugin("java", java -> {
            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            SourceSet main = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            Directory projectDirectory = project.getLayout().getProjectDirectory();

            TaskProvider<RwconfigCheck> check = project.getTasks().register("rwconfigCheck", RwconfigCheck.class, task -> {
                task.setGroup("verification");
                task.setDescription("Checks the Java sources against the rwconfig file.");
                task.getAnalyzerClasspath().from(analyzer);
                // a file collection, not a List.of, for the same reason as above
                task.getRwconfigCandidates().from(extension.getFile().<Object>map(file -> file).orElse(
                    project.files(projectDirectory.file("src/main/resources/rwconfig"), projectDirectory.file("rwconfig"))));
                task.getSourceDirectories().from(main.getJava().getSourceDirectories());
                task.getSources().from(main.getJava());
                task.getFailOnError().set(extension.getFailOnError());
                task.getReportUnread().set(extension.getReportUnread());
                task.getSkipRules().set(extension.getSkipRules());
                task.getSkip().set(extension.getSkip());
                task.getJavaLauncher().set(toolchains.launcherFor(javaExtension.getToolchain()));
                task.getReport().set(project.getLayout().getBuildDirectory().file("reports/rwconfig/findings.txt"));
                task.onlyIf("rwconfig.skip is not set", t -> !((RwconfigCheck) t).getSkip().get());
            });
            project.getTasks().named(main.getCompileJavaTaskName(), task -> task.dependsOn(check));
            project.getTasks().named("check", task -> task.dependsOn(check));
        });
    }

    private static Provider<Boolean> flag(ProviderFactory providers, String name, boolean fallback) {
        return providers.gradleProperty(name).map(Boolean::parseBoolean).orElse(fallback);
    }

    private static String loadVersion() {
        try (InputStream in = RwconfigPlugin.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("version.properties is missing from the plugin jar");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
