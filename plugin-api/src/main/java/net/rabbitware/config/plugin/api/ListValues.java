package net.rabbitware.config.plugin.api;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Writes an array from a nested format - JSON, YAML, and the like - as one list
 * value, the way the bundled plugins do.
 * <p>
 * An array of plain values becomes a single comma-separated value, so it can be
 * read by any list type, and the declared type decides how its items are read.
 * An array holding an object or another array cannot be written that way, and is
 * left to the plugin to flatten with indexed names:
 * <pre>{@code
 * case JSONArray array -> {
 *     if (ListValues.allAreValues(array, MyPlugin::isValue)) {
 *         add(map, prefix, ListValues.join(array, String::valueOf));
 *     } else {
 *         // index the elements
 *     }
 * }
 * }</pre>
 */
public final class ListValues {

    /** What the list splitter trims from the start of each item. */
    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^\\s");

    private ListValues() {
    }

    /**
     * Whether every item is a plain value, so the array can be written as one
     * list value with {@link #join}.
     *
     * @param <T>
     * the type of the items
     * @param items
     * the items of the array
     * @param isValue
     * whether one item is a plain value - a string, number, boolean, or null -
     * rather than an object or an array
     * @return
     * {@code true} if every item is a plain value, {@code false} otherwise
     */
    public static <T> boolean allAreValues(Iterable<T> items, Predicate<? super T> isValue) {
        for (T item : items) {
            if (!isValue.test(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Join the items into one list value, escaped so that each item reads back
     * as exactly the text it started as.
     * <p>
     * An item is taken to be in the syntax of a value already - the same as a
     * single value from the same source - so only what the list syntax adds is
     * escaped:
     * <ul>
     * <li>a comma inside an item is escaped, so it does not split the item;</li>
     * <li>an item starting with whitespace is prefixed with {@code \e}, the
     * empty string. The list splitter trims whitespace after each comma, and
     * would otherwise take it off;</li>
     * <li>a single empty item is written as {@code \e}, since an empty value is
     * an empty list rather than a list holding one empty item.</li>
     * </ul>
     *
     * @param <T>
     * the type of the items
     * @param items
     * the items of the array, all plain values
     * @param text
     * the text of one item, as it would be written as a single value
     * @return
     * the list value
     */
    public static <T> String join(Iterable<T> items, Function<? super T, String> text) {
        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (T item : items) {
            String value = text.apply(item).replace(",", "\\,");
            if (LEADING_WHITESPACE.matcher(value).find()) {
                value = "\\e" + value;
            }
            if (count++ > 0) {
                joined.append(',');
            }
            joined.append(value);
        }
        // only a single empty item joins to nothing, and nothing is an empty list
        return count == 1 && joined.isEmpty() ? "\\e" : joined.toString();
    }
}
