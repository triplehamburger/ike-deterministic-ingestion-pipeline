---
name: fhir-ig-to-asciidoc-fragments
description: Convert a FHIR Implementation Guide package (StructureDefinition, SearchParameter, ValueSet, CodeSystem, CapabilityStatement, OperationDefinition JSON files) into IKE-ASCIIDOC-FRAGMENT topic fragments (.adoc) plus an _includes.adoc, using a deterministic Java command-line tool (one JAR) for everything mechanical and small subagents only for keywords and a summary. Includes validators (structure, coverage, fabrication, syntax, corpus) and a seeded-defect test. Use whenever the user wants to ingest, convert, regenerate, validate or re-run a FHIR IG (US Core or any other) as AsciiDoc/IKE topic fragments, mentions TopicFragmentWriter, topic fragments, IKE ingest, "ingest the IG", or asks to check or improve existing fragment output, even if they do not name this skill.
---

# FHIR IG to IKE-ASCIIDOC-FRAGMENT

Turns the conformance JSON from an IG package into one `.adoc` topic per resource. The design goal is confidence: facts come from the tool, free text is limited to two short fields, and every output is machine-checked against the source.

Why it is built this way: an earlier run used one forked agent per file (about 81 million tokens). Agents then each made small judgement calls, so output drifted (wrapping, extra rows, long-URL handling) and the format did not match the real `TopicFragmentWriter`. Scripts remove that variance; agents are kept only for prose.

## What you need first

1. A package folder holding the IG's `*.json` conformance files (an npm `package/` folder works).
2. A config file. Copy `assets/ig-config.example.json`, edit it (slug, version, citation, URLs, source and work folders; every value in the example is US Core's, so change them all). Paths inside the config are relative to the config file. Pass it to every command as `--config PATH` (or set `FHIR_IG_CONFIG`). Optional keys: `topic_prefix`, `license`, `skip_names`, `skip_prefixes`, `skip_suffixes`, `skip_resource_types`. By default `ImplementationGuide-*`, `Basic-*`, `*.openapi.json` and npm metadata files are skipped.
3. Java 21 or later (`java -version`). No other dependencies: the tool is one JAR, `scripts/fhir-ig-fragments.jar` in this skill folder. Below, `ig` stands for `java -jar "<skill folder>/scripts/fhir-ig-fragments.jar"`; its first argument is the command. Quote any path that contains spaces. Run it from any folder: it writes only where you tell it to.

What each config key does:

| Key | Effect |
|---|---|
| `ig_slug` | Topic ids (`ext-standards-<slug>-<segment>-<id>`) and the first keyword of every file. `topic_prefix` overrides the id prefix. |
| `ig_version`, `fhir_version`, `citation`, `license` | The header attributes of every file. |
| `ig_url` | The one URL the fabrication check accepts although it is not in the source. It is not written anywhere else. |
| `ig_dir_name` | The `// IG:` line in `_includes.adoc` only. Any label, for example `acme-core-1-0-0`. |
| `canonical_namespace` | Only the informational "references with no topic" report in `corpus-check`. Use the URL prefix that the resources' `url` fields share, which for a derived guide may differ from `ig_url`. |
| `source_dir`, `work_dir` | Where the JSON is and where facts, prose and output go. A tidy layout: `<run>/work/ig-config.json` with `"work_dir": "."` and `"source_dir": "../package"`. |

The Identity and Publishing Metadata tables show the source resource's own version, publisher and dates, not the config's. For a guide derived from another one, a table can show 9.0.0 next to an `ig-version` of 1.0.0. That is deliberate (nothing is invented); change the source JSON if the tables must say otherwise. Output file names come from resource ids, so they keep the source's prefix.

Command arguments (every single-IG command takes `--config CFG`). Call the JAR by absolute path. When auditing, read `license` (and the citation) from an existing fragment rather than guessing, because any difference fails every file:

| Command | Arguments |
|---|---|
| `ig extract-facts` | `[FACTS_DIR]` (default `W/facts`) |
| `ig render` | `FACTS_DIR PROSE_DIR OUT_DIR [--stub]` |
| `ig lint` | `OUT_DIR FACTS_DIR PROSE_DIR`, or `OUT_DIR FACTS_DIR --derive-prose`, or add `--allow-stub` |
| `ig corpus-check` | `OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]` (the only option that writes into OUT_DIR) |
| `ig mutate` | `OUT_DIR FACTS_DIR [PROSE_DIR]` |
| `ig batch` | `plan ROOT --work-root WORK`, `status --work-root WORK`, `finish --work-root WORK` (no `--config`; see "Running on a directory of IGs") |

## Workflow

`W` below is the config's `work_dir`, resolved against the config file's folder. Use absolute paths for `W/...` on the command line so nothing is written into the skill folder.

1. **Extract facts**
   `ig extract-facts --config CFG` writes `W/facts/<file>.facts.json` for every resource, with snapshot cardinality resolved. Exit 1 means a warning (the source has a shape the renderer does not handle); exit 2 means a resource type the skill does not support. Fix or deliberately skip (`skip_resource_types`) before continuing.
2. **Prose, from small agents** (see `references/prose-brief.md`)
   Each resource needs `W/prose/<run>/<out_file>.prose.json` (`out_file` already ends in `.adoc`, so the file is `OperationDefinition-docref.adoc.prose.json`) holding `keywords` and `summary`. Spawn general-purpose subagents with the prompt template below. If you cannot spawn subagents (for example you are running as one), write the prose yourself, one file per resource, following the same brief and schema. Both haiku and Sonnet passed every script check in the pilot. Every subagent costs a fixed overhead (about 57–64k tokens for one resource), so give each agent a batch of about 10 resources. In the US Core 9.0.0 run, 21 batch agents (Sonnet) wrote prose for all 208 resources, cost 1.88M tokens in total (about 90k per agent, roughly 6.5 times cheaper than one agent per resource) and passed every check. Group resources of one type together, write one manifest file per batch (one `FACTS_FILE=… PROSE_FILE=…` line per resource) and tell each agent to read its manifest. The brief is per resource: each agent writes one prose file per resource. At most 20 subagents can run at once (the limit may differ in your setup), so start the rest as agents finish.
3. **Render**
   `ig render --config CFG W/facts W/prose/<run> W/out`. It exits 1 if any resource has no prose file (or nothing was rendered). Add `--stub` only to test the pipeline without agents: stub files carry placeholder prose and `lint` rejects them unless you pass `--allow-stub`.
4. **Validate**
   - `ig lint --config CFG W/out W/facts W/prose/<run>`: structure, whole-file render equality, coverage, fabrication, prose rules, syntax. Must be 0 failures. A missing prose file is a failure.
   - `ig corpus-check --config CFG W/out W/facts --write-includes YYYY-MM-DD`: unique ids, no missing or extra files, writes `_includes.adoc`.
   - `ig mutate --config CFG W/out W/facts W/prose/<run>`: injects faults (about 80 to 90, depending on the package) and confirms the validators catch them. Faults that apply to no file in the package are listed as NOT APPLICABLE and are not counted. Re-run it after changing the tool.
   - `lint` and `render` exit 1 when they find or write nothing, so an empty or mistyped path cannot pass.
5. **Deliver** the files in `W/out` to the target folder. Report counts per type and, if you used agents, per-agent token usage (record each agent's `total_tokens` as its notification arrives; it is not stored anywhere else).

## Running on a directory of IGs

Use `ig batch` when the user points at a folder that holds several IGs (each an npm-style package: a folder with a `package.json` plus the conformance JSON, for example `ROOT/<anything>/package/`). It builds each IG's config from its `package.json`, so no hand-made config is needed. Three steps:

1. `ig batch plan ROOT --work-root WORK` finds every IG, writes `WORK/<ig>/ig-config.json`, extracts facts and cuts the resources into prose batches (`WORK/<ig>/batches/batch-NN.txt`, about 10 resources each; `--batch-size N` changes it). It writes `WORK/plan.json` with, per IG, the config, folders, `ig_name` and `ig_slug`. An IG that cannot be extracted is marked blocked with the reason and the others carry on. Unsupported resource types (ConceptMap, StructureMap and similar) block an IG until you pass `--skip-types T1,T2` on purpose; the skip is recorded in that IG's config.
2. Run the prose agents yourself: one agent per manifest, prompt template above, with `IG_NAME` and `IG_SLUG` taken from that IG's plan entry. Agents for different IGs can run side by side (keep to the concurrency limit). `ig batch status --work-root WORK` shows prose progress per IG.
3. `ig batch finish --work-root WORK [--mutate]` renders, lints and corpus-checks every IG whose prose is complete (it writes `_includes.adoc`) and prints one table with a row per IG. `--only NAME` limits it to some IGs. Nothing leaves `WORK` unless you add `--deliver OUT_ROOT`; then each IG that passed is copied to `OUT_ROOT/<ig_dir_name>/`, and an existing non-empty folder is never overwritten without `--overwrite`. The exit code is 1 if any IG did not pass.

Generated config values, from `package.json`: `ig_slug` = package name without `hl7.fhir.` and with dots as hyphens (`hl7.fhir.us.core` becomes `us-core`); `ig_dir_name` = name plus version (`hl7-us-core-9-0-0`); `citation` = `author. title, vversion. url/`; `ig_url` = `url/`; `canonical_namespace` = `canonical/`; `fhir_version` = first of `fhirVersions`. Change any of them per package in `ROOT/ig-overrides.json` (or `--overrides FILE`), for example `{"hl7.fhir.us.core": {"ig_slug": "core", "license": "..."}}`. Two IGs with the same name and version are not both run. Check each IG's generated citation and slug before delivering; they are only as good as its `package.json`.

Tested on the real US Core 9.0.0 package (output identical to the single-IG run, every seeded fault caught) and on a second, synthetic IG. No other real IG has been run, so expect to meet resource shapes the extractor does not handle yet.

## Auditing fragments you did not just generate

Use this when asked whether existing fragments are still correct (nothing is rendered or changed):

1. Put a config in a scratch folder (not the fragments folder), with `source_dir` pointing at the source JSON and `work_dir` at the scratch folder. Run `extract-facts`.
2. `ig lint --config CFG FRAGMENTS_DIR FACTS_DIR --derive-prose`. It rebuilds keywords and summary from each fragment, checks them against the prose rules, and compares everything else with a fresh render. It cannot detect an edit to those two fields that still follows the prose rules.
3. If the fragments carry stub prose (placeholder summary, a `stub` keyword), `--derive-prose` fails every file on the prose rules. Run `lint CFG FRAGMENTS_DIR FACTS_DIR --allow-stub` instead; it compares each file with a render built from the stub prose, so only genuine differences fail.
4. `corpus-check` without `--write-includes` checks counts and ids and writes nothing. `lint` and `corpus-check` never modify the fragments folder.
5. Report each failing file with the failing layer's message, and say which files passed. Do not fix the fragments unless asked.

Prompt template for a prose agent (substitute the values; the brief file holds the rules):

```
You are a prose agent in a FHIR to AsciiDoc pipeline. Your complete instructions are in `<skill>/references/prose-brief.md`. Read it first; use only the part from "## Your task" through "## Report back".
Placeholders: IG_NAME=<e.g. US Core 9.0.0>  IG_SLUG=<e.g. us-core>
Resources (one prose file each):
- FACTS_FILE=<path>  PROSE_FILE=<path>
...
Do not read any other file.
```

## Output format (matches the real writer)

The `== Overview` section contains only `{topic-summary}`: the one-sentence summary is written once, in the header, and the Overview references it. The source description is not printed in the fragment.


Four comment lines (`// topicId`, `// Topic:`, `// Type:`, `// Status:`), then `:topic-*:` attributes in fixed order, blank line, `[[topicId]]`, `= title`, blank line, body. `citation`, `license` and `keywords` are never wrapped; `summary`, `canonical`, `resource-type`, `ig-version` and `fhir-version` wrap at 92 characters with ` \` and two-space continuation, and an unbreakable first word stays inline. LF line endings, UTF-8, no BOM, one trailing newline. Full details and the per-type section skeletons are in `references/render-spec.md`; read it before changing the renderer.

Every file is named `<ResourceType>-<id>.adoc` (for example `StructureDefinition-us-core-patient.adoc`) in one flat folder per IG, so the type is visible in the name and ids shared across types cannot collide. TopicIds are unchanged (`ext-standards-<slug>-<segment>-<id>`). Prose files are named `<out_file>.prose.json`, so they carry the same prefix.

## Rules that keep the output trustworthy

- Never invent a value. A missing source field renders as `Not specified in source`; a missing SearchParameter title falls back to its `name`.
- Do not hand-edit generated `.adoc` files. Change the facts, renderer or prose, re-render and re-validate; render equality compares the whole file with a fresh render, so a hand edit fails (except an edit to keywords or summary when checked with `--derive-prose`).
- The decisions D1–D16 in `references/render-spec.md` were confirmed by the user. Change one only with their agreement.
- Do not edit the validators to make a failure go away. First decide whether the output or the check is wrong, and prove it with `mutate`.

## Status

The tool is a Java 21 port of the original Python scripts (Maven project `network.ike.docs:fhir-ig-fragments`). On US Core 9.0.0 it produced facts, all 208 fragments and `_includes.adoc` byte-identical to the Python output, and the seeded-defect test printed the same 96 of 96 result, file by file. The Java unit and integration tests run the pipeline on the 6-resource package against golden files the Python version wrote.

Layers L0–L6 and the seeded-defect test are implemented and tested on US Core 9.0.0 (208 resources, every seeded fault caught) and on a 6-resource package. Pilot: 16 resources on two models, all script checks passed. Full run: all 208 US Core resources with 21 batched Sonnet agents (10 resources each), 0 lint failures, 0 corpus errors, 96 of 96 seeded faults caught (after the Overview and URL-per-line changes). Not built: the LLM verifier (L7), golden files, a run log and a retry loop. Nothing checks that a summary is semantically right. Value-level tampering (cardinality, bindings, codes) is caught by render equality, which trusts the extractor. `references/validation-spec.md` lists what each layer checks, the known gaps and the backlog.
