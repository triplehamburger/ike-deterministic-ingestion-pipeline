package network.ike.docs.fhirfragments;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Command-line entry point. {@code java -jar fhir-ig-fragments.jar COMMAND [options]}.
 *
 * <pre>
 * extract-facts --config CFG [FACTS_DIR]
 * render        --config CFG FACTS_DIR PROSE_DIR OUT_DIR [--stub]
 * lint          --config CFG OUT_DIR FACTS_DIR PROSE_DIR | OUT_DIR FACTS_DIR --derive-prose
 *                                                        | OUT_DIR FACTS_DIR --allow-stub [PROSE_DIR]
 * corpus-check  --config CFG OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]
 * mutate        --config CFG OUT_DIR FACTS_DIR [PROSE_DIR]
 * batch plan|status|finish ...   (a directory of IGs; no --config)
 * </pre>
 *
 * Exit codes: 0 success; 1 a check failed or a usage error; 2 (extract-facts) an unsupported resource type.
 */
public final class Main {
    static final String USAGE = String.join("\n",
            "usage: java -jar fhir-ig-fragments.jar COMMAND [options]",
            "",
            "Single IG (every command takes --config ig-config.json, or set FHIR_IG_CONFIG):",
            "  extract-facts --config CFG [FACTS_DIR]",
            "  render        --config CFG FACTS_DIR PROSE_DIR OUT_DIR [--stub]",
            "  lint          --config CFG OUT_DIR FACTS_DIR PROSE_DIR",
            "  lint          --config CFG OUT_DIR FACTS_DIR --derive-prose",
            "  lint          --config CFG OUT_DIR FACTS_DIR --allow-stub [PROSE_DIR]",
            "  corpus-check  --config CFG OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]",
            "  mutate        --config CFG OUT_DIR FACTS_DIR [PROSE_DIR]",
            "",
            "Directory of IGs (no --config):",
            "  batch plan   ROOT --work-root WORK [--batch-size N] [--only NAME ...] [--overrides FILE]",
            "                    [--skip-types T1,T2]",
            "  batch status --work-root WORK",
            "  batch finish --work-root WORK [--only NAME ...] [--mutate] [--allow-stub] [--date YYYY-MM-DD]",
            "                    [--deliver OUT_ROOT [--overwrite]]");

    private Main() {
    }

    public static void main(String[] argv) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        int rc = run(argv, out);
        out.flush();
        System.exit(rc);
    }

    /** Run a command; returns the exit code. Output goes to {@code out}. */
    public static int run(String[] argv, PrintStream out) {
        if (argv.length == 0 || List.of("-h", "--help", "help").contains(argv[0])) {
            out.println(USAGE);
            return argv.length == 0 ? 1 : 0;
        }
        String cmd = argv[0];
        List<String> rest = Arrays.asList(argv).subList(1, argv.length);
        try {
            if (cmd.equals("batch")) {
                return IgBatch.main(rest, out);
            }
            Args a = new Args(rest, Set.of("--config", "--write-includes"));
            IgConfig cfg = IgConfig.load(configPath(a));
            Renderer renderer = new Renderer(cfg);
            return switch (cmd) {
                case "extract-facts" -> new FactsExtractor(cfg).run(a, out);
                case "render" -> renderer.run(a, out);
                case "lint" -> new Linter(cfg, renderer).run(a, out);
                case "corpus-check" -> new CorpusChecker(cfg, renderer).run(a, out);
                case "mutate" -> new Mutator(cfg, new Linter(cfg, renderer)).run(a, out);
                default -> throw new CliException("unknown command: " + cmd + "\n\n" + USAGE);
            };
        } catch (CliException e) {
            out.println(e.getMessage());
            return 1;
        }
    }

    private static Path configPath(Args a) {
        String p = a.value("--config");
        if (p == null) {
            p = System.getenv("FHIR_IG_CONFIG");
        }
        if (p == null || p.isEmpty()) {
            throw new CliException("config required: pass --config ig-config.json or set FHIR_IG_CONFIG");
        }
        return Path.of(p).toAbsolutePath();
    }
}
