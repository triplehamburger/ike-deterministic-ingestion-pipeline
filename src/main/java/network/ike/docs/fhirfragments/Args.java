package network.ike.docs.fhirfragments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal command-line parsing: positional arguments, boolean flags, and options that take values (a value
 * option may repeat or take several values up to the next option, for example {@code --only A B}).
 */
final class Args {
    private final List<String> positional = new ArrayList<>();
    private final Map<String, List<String>> options = new HashMap<>();

    /**
     * @param argv          the arguments after the command name
     * @param valueOptions  names (with leading dashes) of options that take values; every other
     *                      {@code --x} token is a flag
     * @param multiValue    the subset of value options that take several values
     */
    Args(List<String> argv, Set<String> valueOptions, Set<String> multiValue) {
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (!a.startsWith("--")) {
                positional.add(a);
                continue;
            }
            List<String> vals = options.computeIfAbsent(a, k -> new ArrayList<>());
            if (valueOptions.contains(a)) {
                if (i + 1 >= argv.size() || argv.get(i + 1).startsWith("--")) {
                    throw new CliException("option " + a + " needs a value");
                }
                vals.add(argv.get(++i));
                while (multiValue.contains(a) && i + 1 < argv.size() && !argv.get(i + 1).startsWith("--")) {
                    vals.add(argv.get(++i));
                }
            }
        }
    }

    Args(List<String> argv, Set<String> valueOptions) {
        this(argv, valueOptions, Set.of());
    }

    List<String> positional() {
        return positional;
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }

    /** First value of an option, or null when absent. */
    String value(String name) {
        List<String> v = options.get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    /** All values of an option (empty when absent). */
    List<String> values(String name) {
        List<String> v = options.get(name);
        return v == null ? List.of() : v;
    }
}
