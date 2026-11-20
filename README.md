# fhir-ig-fragments

Turns the conformance resources of a FHIR Implementation Guide (StructureDefinition, SearchParameter, ValueSet,
CodeSystem, CapabilityStatement, OperationDefinition) into **IKE-ASCIIDOC-FRAGMENT** topic fragments (`.adoc`) plus an
`_includes.adoc`, and validates them against the source.

Everything mechanical is deterministic Java. The only free text in a fragment is two short fields, `keywords` and one
`summary` sentence, which an LLM agent writes and a validator checks. The same repository packages the tool as a
Claude skill (`fhir-ig-to-asciidoc-fragments`) that drives the pipeline.

## Build

Needs JDK 21+ and Maven 3.9+ (Maven 4 also works).

```bash
mvn verify
```

This compiles, runs the tests, and produces in `target/`:

| File | What it is |
|---|---|
| `fhir-ig-fragments-<version>.jar` | Runnable JAR with its dependencies (`java -jar ...`) |
| `fhir-ig-to-asciidoc-fragments-v<version>.skill` | The Claude skill: `src/skill/` plus the JAR as `scripts/fhir-ig-fragments.jar` |

`mvn install` also installs the `.skill` as an attached artifact (type `skill`).

## Use

```bash
java -jar target/fhir-ig-fragments-3.0.0.jar help
```

Single IG, with an `ig-config.json` (see `src/skill/fhir-ig-to-asciidoc-fragments/assets/ig-config.example.json`):

```text
extract-facts --config CFG [FACTS_DIR]                      source JSON -> facts
render        --config CFG FACTS_DIR PROSE_DIR OUT_DIR      facts + prose -> .adoc
lint          --config CFG OUT_DIR FACTS_DIR PROSE_DIR      validators L1-L4, L6 (or --derive-prose / --allow-stub)
corpus-check  --config CFG OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]
mutate        --config CFG OUT_DIR FACTS_DIR [PROSE_DIR]    seeded-defect test of the validators
```

A directory of IGs (each an npm-style package with a `package.json`), no config needed:

```text
batch plan   ROOT --work-root WORK     find IGs, derive configs, extract facts, cut prose batches
batch status --work-root WORK          prose progress per IG
batch finish --work-root WORK [--mutate] [--deliver OUT_ROOT [--overwrite]]
```

Exit codes: 0 success, 1 a check failed (or a usage error), 2 `extract-facts` met an unsupported resource type.
The prose step (one agent per batch manifest) is described in
`src/skill/fhir-ig-to-asciidoc-fragments/SKILL.md` and `references/prose-brief.md`.

## Layout

```text
pom.xml
src/main/java/network/ike/docs/fhirfragments/
    Main              command-line dispatch
    IgConfig, Args    configuration and argument parsing
    FactsExtractor    source JSON -> facts.json
    Renderer          facts + prose -> .adoc, _includes.adoc
    Linter            L1 structure, L1b render equality, L2 coverage, L3 fabrication, L4 prose, L6 syntax
    CorpusChecker     L5 corpus checks
    Mutator           seeded-defect test
    IgBatch           directory-of-IGs runner
    Text, Json        helpers that match the reference string and JSON behaviour
src/test/java/...     unit and integration tests
src/test/resources/golden/mini/   golden fragments for the 6-resource package
src/skill/fhir-ig-to-asciidoc-fragments/   the skill: SKILL.md, references/, assets/, evals/
```

The tests read the skill's eval fixtures (`evals/*/files`) directly, so there is one copy of the sample package.

## Origin and verification

This is a Java port of the Python scripts the skill used before (versions up to v2). On the real US Core 9.0.0
package (208 resources) the port produced facts, fragments and `_includes.adoc` byte-identical to the Python output;
`lint` reports 0 failures, `corpus-check` 0 errors and `mutate` catches 96 of 96 seeded faults, with the same
per-file result as Python. The golden files under `src/test/resources/golden/mini` were written by the Python version.
