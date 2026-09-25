package net.rabbitware.config.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code rwconfig { }} block. Every setting matches one of the Maven
 * plugin's, and can also be given as a Gradle property with the same name - so
 * {@code -Prwconfig.skip=true} works as {@code -Drwconfig.skip=true} does there.
 */
public abstract class RwconfigExtension {

    /** Created by Gradle, which supplies the abstract properties. */
    public RwconfigExtension() {
    }

    /**
     * Where the {@code rwconfig} file is. When unset, the usual places are
     * searched: {@code src/main/resources/rwconfig}, then {@code rwconfig} in
     * the project directory. A project with neither is not checked.
     *
     * @return the property
     */
    public abstract RegularFileProperty getFile();

    /**
     * Whether a finding of error severity fails the build. Defaults to true.
     *
     * @return the property
     */
    public abstract Property<Boolean> getFailOnError();

    /**
     * Whether to report properties that are declared but never read. Defaults
     * to true.
     *
     * @return the property
     */
    public abstract Property<Boolean> getReportUnread();

    /**
     * Rules to ignore, by id - for example {@code unread-property}.
     *
     * @return the property
     */
    public abstract ListProperty<String> getSkipRules();

    /**
     * Skip the check entirely. Defaults to false.
     *
     * @return the property
     */
    public abstract Property<Boolean> getSkip();
}
