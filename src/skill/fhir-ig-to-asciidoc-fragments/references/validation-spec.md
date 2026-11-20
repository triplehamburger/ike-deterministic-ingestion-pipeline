# Validation spec

Goal: be able to say, with evidence, that every generated file matches its source and the real writer format. This file describes what is built and what is not. Anything not listed under "Built" does not exist yet.

## Built

All script layers must report zero failures. `lint` runs L1 to L4 and L6, `corpus-check` runs L5, `extract-facts` runs L0.

| ID | Layer | What the code checks | Catches |
|---|---|---|---|
| L0 | Extraction (`extract-facts`) | Unsupported resource type stops the run (exit 2) unless listed in `skip_resource_types`. Warnings stop the run (exit 1): differential element with no snapshot row, nested CodeSystem concepts, nested OperationDefinition parameters, comparator/`_comparator` length mismatch, a CapabilityStatement without exactly one `rest`. Stale `*.facts.json` are removed first. Counts are stored in `prose_inputs.counts`. | Source shapes the renderer does not handle |
| L1 | Structure | The Overview body is exactly `{topic-summary}`; four comment lines; attribute set and order; fixed values (id, type, status, provenance, citation, license, canonical, resource type, versions) equal the expected ones; wrapped attributes equal `attribute_line()` output and unwrapped ones are not wrapped; keywords start with the three fixed ones, are kebab-case and unique; anchor, title and blank lines; section names, order and required sections; LF only, no BOM, one trailing newline; filename equals `out_file` | Format drift |
| L1b | Render equality | When prose is available (a prose file, or `--derive-prose`) the whole file, header, Overview and body, must equal a fresh render from facts plus that prose. Any hand edit fails | Edits, wrong values, anything the renderer would not produce |
| L2 | Coverage | Using the raw source JSON as oracle: each differential element id, binding value set, constraint key (profiles only), compose code, display, filter and system, concept code and display, comparator, interaction, search parameter, supported profile, operation parameter name, canonical URL appears in the body | Missing items |
| L3 | Fabrication | Every URL and every dotted number of four or more parts in the body, occurs in the raw source JSON (the IG URL is allowed) | Invented identifiers |
| L4 | Prose | Schema (exact keys, 2 to 5 kebab-case keywords that are not the fixed ones, one-sentence summary of 60 to 200 characters ending in a full stop), none of these characters: pipe, backtick, asterisk, underscore, square or angle brackets, line break, em dash, no banned opening phrase, and every URL or number in the summary appears in `prose_inputs`. A missing prose file is a failure | Agent output that breaks the rules |
| L5 | Corpus (`corpus-check`) | Facts count equals the source manifest; topic ids and filenames unique; no missing or extra output files; topic id inside each file; every include resolves, is unique and matches the facts count. References into the IG namespace that have no topic are printed for information only. `--write-includes DATE` writes `_includes.adoc` | Missing, extra or orphaned files |
| L6 | Syntax lint | Table delimiters balanced, every row starts with `\|` and has the declared number of cells (a row may continue on following lines while a line ends with ` +`), no two URLs joined by a comma in a table cell, heading levels do not jump | AsciiDoc that would render wrongly |

### Prose modes in `lint`

- `lint ... PROSE_DIR`: normal. Each file is compared with a render from its prose file.
- `lint ... --derive-prose`: for auditing fragments that have no prose files. Keywords and summary are rebuilt from the fragment, so an edit to those two fields is not detected unless it breaks the L4 rules. Everything else is still compared with a fresh render.
- `lint ... --allow-stub [PROSE_DIR]`: pipeline tests only. Files without a prose file are compared with the stub prose.

## Seeded-defect test (`mutate`)

`mutate CFG OUT FACTS [PROSE_DIR]` copies the text of up to three applicable files per fault (first, middle, last), injects one fault, and confirms a layer reports it. It also runs the validators on every unmutated file first, because a baseline that already fails would make every fault look caught. Faults: cardinality, binding strength, binding row dropped, code changed, invented URL, invented OID-like number, section deleted or swapped, attribute order, wrapped citation, CRLF, BOM, extra blank line, anchor mismatch, extra table column, unescaped pipe, comparator expectation, title, trailing newline, canonical value, Publishing Metadata edited or removed, summary, keywords and Overview text edited by hand, a pasted copy of the summary in the Overview, a removed Overview reference, and URLs joined by commas in a table cell; plus 12 prose-rule faults. Faults that apply to no file are listed as NOT APPLICABLE and not counted. The number of faults depends on the package (US Core 9.0.0: 96; a 6-file package: about 80). Re-run it after changing any script.

## Known gaps

- **The wrap check is not independent.** L1 compares wrapped lines with `attribute_line()`, the function the renderer uses. It was compared with the real `TopicFragmentWriter` once, by hand, when the skill was built.
- **L2 is a presence check.** It confirms a value appears somewhere in the body, not in the right cell. Min, max, binding strength and expectations are protected by L1b, which trusts the extractor.
- **L3 checks URLs and long dotted numbers only.** Codes and short numbers are not checked.
- **Grounding of short numbers is weak.** A single digit in a summary matches any digit in `prose_inputs`.
- **Semantic mistakes in prose are not detected.** A summary can be well formed and untrue.
- **Rules in the prose brief that no script checks:** the prose may not state obligations (SHALL, SHOULD, MAY) the description does not, no hedging, ASCII punctuation only.
- **Extension constraints are not rendered** (decision D15), so they are not covered.
- **Tested on US Core 9.0.0 only.** Other guides may contain resource shapes the extractor does not handle yet; it stops with a warning or an unsupported-type error rather than guessing.

## Backlog (not built)

- **L7 LLM verifier.** One agent per file, separate from the author, no knowledge of the generation logic. Inputs: facts, rendered file, prose. It judges only: (a) summary and keywords are supported by the facts and say nothing false; (b) the converted description keeps the source meaning; (c) no sentence in the body claims something the facts do not show. Output `{"id", "verdict": "pass|fail", "issues": [{"severity": "major|minor", "where", "what"}]}`; any `major` fails the file. Calibrate with seeded semantic defects before use (a verifier that misses more than 10% is not trusted).
- **Golden files:** one hand-reviewed file per resource type that the renderer must reproduce byte for byte.
- **Run log:** per file, the model, source and prose hashes, token usage and validator results.
- **Retry loop:** retry a failing prose file once with the validator message appended; a second failure goes on a manual-review list.
- **L0 filename check:** confirm the source file name matches its resource type and id.

## Pilot method

A good pilot picks about 16 resources that cover each type and each awkward shape in the guide (slices, large profile, invariants, extensions with many contexts, comparators, duplicate ids across types, exclude rules, filters, an operation, a capability statement). Run the prose agent on two models and compare L4 pass rate, cost and run-to-run variation; rendered bodies are deterministic, so only prose varies. In the US Core 9.0.0 pilot both models passed every script check.

## Cost

See `SKILL.md`: each subagent costs about 57 to 64 thousand tokens whatever the task, so batching resources per agent is the main lever. Measured on US Core 9.0.0: 21 agents of about 10 resources each used 1.88M tokens in total (about 90k per agent; 79k to 141k, the first batch being the most expensive), against about 60k per agent for one resource. That is about 9k tokens per resource instead of about 60k.
