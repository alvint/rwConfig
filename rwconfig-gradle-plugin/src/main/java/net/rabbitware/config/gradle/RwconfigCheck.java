package net.rabbitware.config.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.work.DisableCachingByDefault;

/**
 * Checks a project's Java sources against its {@code rwconfig} file.
 *
 * <p>The analyzer runs in a JVM of its own, from the project's Java toolchain.
 * It needs Java 21 and Gradle 9 runs on 17, and running it apart also keeps
 * its dependencies - slf4j among them - out of Gradle's own class path.
 */
@DisableCachingByDefault(because = "the check is quick next to the build it guards, and its report names absolute paths")
public abstract class RwconfigCheck extends DefaultTask {

    static final String ANALYZER_MAIN = "net.rabbitware.config.analyzer.Main";

    /** Created by Gradle, which supplies the abstract properties. */
    public RwconfigCheck() {
    }

    /**
     * The analyzer and everything it needs.
     *
     * @return the class path
     */
    @Classpath
    public abstract ConfigurableFileCollection getAnalyzerClasspath();

    /**
     * Where the {@code rwconfig} file may be, in order. The first that exists
     * is checked, and a project with none is not - which is decided when the
     * task runs, so a file added later is noticed.
     *
     * @return the candidates
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRwconfigCandidates();

    /**
     * The source directories, handed to the analyzer. Their contents are
     * tracked through {@link #getSources()}.
     *
     * @return the directories
     */
    @Internal
    public abstract ConfigurableFileCollection getSourceDirectories();

    /**
     * The Java sources, tracked so that a change to any of them runs the check
     * again.
     *
     * @return the sources
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getSources();

    /**
     * Whether a finding of error severity fails the build.
     *
     * @return the property
     */
    @Input
    public abstract Property<Boolean> getFailOnError();

    /**
     * Whether to report properties that are declared but never read.
     *
     * @return the property
     */
    @Input
    public abstract Property<Boolean> getReportUnread();

    /**
     * Rules to ignore, by id.
     *
     * @return the property
     */
    @Input
    public abstract ListProperty<String> getSkipRules();

    /**
     * Whether the check is skipped. Not an input: a skipped task does not run.
     *
     * @return the property
     */
    @Internal
    public abstract Property<Boolean> getSkip();

    /**
     * The JVM the analyzer runs in.
     *
     * @return the property
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The findings reported, one per line - what the task last said, kept so
     * that Gradle can tell when nothing has changed.
     *
     * @return the property
     */
    @OutputFile
    public abstract RegularFileProperty getReport();

    /**
     * Runs the analyzer.
     *
     * @return the service
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /**
     * Runs the analyzer, reports what it found, and fails the build if asked
     * to and anything it found is an error.
     */
    @TaskAction
    public void check() {
        File report = getReport().get().getAsFile();
        File config = getRwconfigCandidates().getFiles().stream()
            .filter(File::isFile)
            .findFirst()
            .orElse(null);
        if (config == null) {
            getLogger().info("no `rwconfig` file in this project - nothing to check");
            write(report, List.of());
            return;
        }

        List<Finding> reported = reported(analyze(config));
        List<String> lines = new ArrayList<>();
        for (Finding finding : reported) {
            String line = finding.describe();
            lines.add(line);
            switch (finding.severity()) {
                case "ERROR" -> getLogger().error(line);
                case "WARNING" -> getLogger().warn(line);
                // shown by default, as the Maven plugin shows them - an unread
                // property is worth seeing without asking for more output
                default -> getLogger().lifecycle(line);
            }
        }
        write(report, lines);

        long errors = reported.stream().filter(Finding::isError).count();
        if (reported.isEmpty()) {
            getLogger().info("rwconfig: checked {} against the sources, nothing to report", config);
        }
        if (errors > 0 && getFailOnError().get()) {
            throw new GradleException(
                errors + (errors == 1 ? " problem" : " problems") + " found checking the code against " + config);
        }
    }

    /** Every finding the analyzer makes, before any is filtered out. */
    private List<Finding> analyze(File config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecResult result = getExecOperations().javaexec(spec -> {
            spec.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile());
            spec.setClasspath(getAnalyzerClasspath());
            spec.getMainClass().set(ANALYZER_MAIN);
            spec.args("check", "--rwconfig", config.getAbsolutePath());
            for (File directory : getSourceDirectories().getFiles()) {
                if (directory.isDirectory()) {
                    spec.args("--source", directory.getAbsolutePath());
                }
            }
            spec.setStandardOutput(out);
            spec.setErrorOutput(err);
            spec.setIgnoreExitValue(true);
        });
        String output = out.toString(StandardCharsets.UTF_8);
        if (result.getExitValue() != 0) {
            throw new GradleException(
                "the rwConfig analyzer failed (exit " + result.getExitValue() + ")\n"
                + err.toString(StandardCharsets.UTF_8) + output);
        }
        List<Finding> findings = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                try {
                    findings.add(Finding.parse(line));
                } catch (IllegalArgumentException e) {
                    throw new GradleException("could not read the rwConfig analyzer's output: " + e.getMessage(), e);
                }
            }
        }
        return findings;
    }

    /** The findings left once the skipped rules, and unread properties if asked, are taken out. */
    private List<Finding> reported(List<Finding> findings) {
        Set<String> skipped = Set.copyOf(getSkipRules().get());
        boolean unread = getReportUnread().get();
        return findings.stream()
            .filter(f -> !skipped.contains(f.rule()))
            .filter(f -> unread || !"unread-property".equals(f.rule()))
            .toList();
    }

    private static void write(File report, List<String> lines) {
        try {
            Files.createDirectories(report.toPath().getParent());
            Files.write(report.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
