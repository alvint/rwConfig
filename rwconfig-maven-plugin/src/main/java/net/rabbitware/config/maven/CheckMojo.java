package net.rabbitware.config.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import net.rabbitware.config.analyzer.Finding;
import net.rabbitware.config.analyzer.RwconfigAnalyzer;

/**
 * Checks the project's Java sources against its {@code rwconfig} file.
 *
 * <p>Does nothing at all when the project has no {@code rwconfig} - the plugin
 * is meant to be harmless to leave configured in a parent pom.
 */
// PROCESS_SOURCES rather than a later phase: only the sources are read, so
// there is no reason to compile first - and a misread property is more useful
// reported before the compiler has spent time on the module than after.
@Mojo(name = "check", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Where the `rwconfig` file is. When unset, the usual places are searched:
     * `src/main/resources/rwconfig` and then `rwconfig` in the project root.
     */
    @Parameter(property = "rwconfig.file")
    private File rwconfigFile;

    /** Whether findings of ERROR severity fail the build. */
    @Parameter(property = "rwconfig.failOnError", defaultValue = "true")
    private boolean failOnError;

    /** Whether to report properties that are declared but never read. */
    @Parameter(property = "rwconfig.reportUnread", defaultValue = "true")
    private boolean reportUnread;

    /** Rules to ignore, by id - for example `unread-property`. */
    @Parameter(property = "rwconfig.skipRules")
    private List<String> skipRules;

    /** Skip the check entirely. */
    @Parameter(property = "rwconfig.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("rwconfig check skipped");
            return;
        }
        Path config = locateConfigFile();
        if (config == null) {
            getLog().debug("no `rwconfig` file in this project - nothing to check");
            return;
        }
        List<Path> roots = project.getCompileSourceRoots().stream().map(Path::of).toList();
        List<Finding> findings;
        try {
            RwconfigAnalyzer analyzer = new RwconfigAnalyzer(
                "file:" + config.toAbsolutePath(), config.getFileName().toString());
            findings = analyzer.analyze(RwconfigAnalyzer.javaSourcesIn(roots));
        } catch (RuntimeException e) {
            // an `rwconfig` file the library itself cannot read is its own
            // error to report, at startup, with a better message than we could
            getLog().warn("could not read " + config + ": " + e.getMessage());
            return;
        }

        Set<String> skipped = skipRules == null ? Set.of() : Set.copyOf(skipRules);
        List<Finding> reported = new ArrayList<>();
        for (Finding finding : findings) {
            if (skipped.contains(finding.rule().id())
                || (!reportUnread && finding.rule() == Finding.Rule.UNREAD_PROPERTY)) {
                continue;
            }
            reported.add(finding);
            String message = describe(finding);
            switch (finding.severity()) {
                case ERROR -> getLog().error(message);
                case WARNING -> getLog().warn(message);
                case INFO -> getLog().info(message);
            }
        }

        long errors = reported.stream().filter(f -> f.severity() == Finding.Severity.ERROR).count();
        if (reported.isEmpty()) {
            getLog().info("rwconfig: checked " + config + " against the sources, nothing to report");
        }
        if (errors > 0 && failOnError) {
            throw new MojoFailureException(
                errors + (errors == 1 ? " problem" : " problems")
                + " found checking the code against " + config
            );
        }
    }

    /** `<file>:<line>: <message> [<rule>]`, which most tools can jump to. */
    private String describe(Finding finding) {
        String where = finding.line() > 0
            ? finding.file() + ":" + finding.line() + ":" + finding.column() + ": "
            : finding.file() + ": ";
        return where + finding.message() + " [" + finding.rule().id() + "]";
    }

    private Path locateConfigFile() {
        if (rwconfigFile != null) {
            return rwconfigFile.exists() ? rwconfigFile.toPath() : null;
        }
        Path base = project.getBasedir().toPath();
        for (Path candidate : List.of(
            base.resolve("src/main/resources/rwconfig"),
            base.resolve("rwconfig")
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
