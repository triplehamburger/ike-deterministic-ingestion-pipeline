# Prose-agent brief (DRAFT v0.1) — self-contained

This is the complete prompt for one subagent. Fill the placeholders `{{FACTS_FILE}}` and `{{PROSE_FILE}}` per resource and the IG placeholders `{{IG_NAME}}` and `{{IG_SLUG}}` once per run and pass the whole text as the prompt. The agent needs no other context.

---

## Your task

You are one step in a pipeline that converts FHIR conformance resources ({{IG_NAME}}) into AsciiDoc topic fragments.
A script has already extracted the facts and will render every header, table and section itself.
**You write only two short pieces of prose** for one resource: `keywords` and `summary`.
You do not write AsciiDoc, tables, headers or file content.

Inputs (read-only):
- `FACTS_FILE`: `{{FACTS_FILE}}` — a JSON file. Use only the `prose_inputs` object inside it.

Output (the only file you create):
- `PROSE_FILE`: `{{PROSE_FILE}}` — one JSON object, written with the Write tool. Do not run shell commands to write it. Do not create any other file or folder.

## `prose_inputs` fields you may use

`resource_type`, `subtype` (`profile` | `extension` | `searchparameter` | `valueset` | `codesystem` | `capabilitystatement` | `operation`), `id`, `name`, `title`, `status`, `description` (source markdown), `purpose` (may be empty), `fhir_type` (the FHIR type a profile or extension constrains, or the base resource of a search parameter), and `counts` (small integers such as number of elements, bindings, concepts or resources).
Treat everything else as unavailable. Never use outside knowledge of FHIR or the IG to add facts.

## Output schema

```json
{
  "id": "<same as prose_inputs.id>",
  "resource_type": "<same as prose_inputs.resource_type>",
  "keywords": ["kebab-case", "…"],
  "summary": "One sentence."
}
```

## Rules

1. **Grounded only.** Every claim must follow from `prose_inputs`. If a fact is not there, leave it out.
2. **No invented identifiers.** Do not write URLs, OIDs, version numbers or code values unless they appear verbatim in `prose_inputs`. Prefer none.
3. **Plain text.** No markdown, no AsciiDoc markup, no backticks, no `|`, no `*`, no `_`, no square or angle brackets, no line breaks, no emoji. Use ordinary ASCII punctuation (an em dash is not allowed; use a comma or a colon). If a name contains an underscore, write it as words (for example "last updated").
4. **`keywords`:** 2 to 5 items. Each matches `^[a-z0-9]+(-[a-z0-9]+)*$`. No duplicates. Do **not** include `{{IG_SLUG}}`, the lowercased resource type, or `fhir` (the script adds the fixed keywords itself). Take them from the name, title, description and `fhir_type`, for example the clinical topic (`blood-pressure`, `allergy`) or the role (`search`, `terminology`, `extension`).
5. **`summary`:** exactly one sentence, 60 to 200 characters, ending with a full stop. It states what the resource defines or constrains, in the pattern for its `subtype`:
   - profile: `Constrains the <fhir_type> resource to define …`
   - extension: `Defines an extension that …`
   - searchparameter: `Defines the <code-like name> search parameter for …`
   - valueset: `Defines the value set of …`
   - codesystem: `Defines the code system of …`
   - capabilitystatement: `Declares the capabilities a … must support …`
   - operation: `Defines the … operation that …`
   If the source `status` is `retired` or `draft`, say so at the end of the sentence, for example `(retired)`.
6. **No obligations beyond the source.** Do not say something SHALL, SHOULD or MAY be done unless the description says so in those words.
7. **No filler.** Do not start with "This resource", "This profile" or "The resource". Do not hedge ("appears to", "likely").
8. **Self-check before writing.** Re-read your two fields and confirm rules 1 to 7 hold. Remove anything you cannot trace to `prose_inputs`.
9. **Malformed input.** If `FACTS_FILE` is missing, is not valid JSON, or lacks `prose_inputs`, write `{"error": "<one line reason>"}` to `PROSE_FILE` and stop.

## Illustrative examples (style only; do not copy)

```json
{"id":"example-profile","resource_type":"StructureDefinition","keywords":["blood-pressure","vital-signs"],
 "summary":"Constrains the Observation resource to define how blood pressure measurements are recorded."}
{"id":"example-sp","resource_type":"SearchParameter","keywords":["patient","search","identifier"],
 "summary":"Defines the identifier search parameter for Patient resources."}
{"id":"example-vs","resource_type":"ValueSet","keywords":["terminology","category"],
 "summary":"Defines the value set of observation categories allowed for screening results."}
```

## Report back

Reply with one line: `OK <PROSE_FILE>` or `ERROR <reason>`. Do not summarise your work.

---

## Notes for the pipeline owner (not part of the prompt)

- Measured cost: about 57 to 64 thousand tokens per agent (mostly fixed subagent overhead), constant across the run for one resource per agent. Batching about 10 resources per agent costs about 90k per agent and has been run on all 208 US Core resources.
- Fixed keywords added by the script: the IG slug, lowercased resource type, and `fhir`.
- The prose JSON is checked by `lint` (layer L4) after rendering; a missing or rejected prose file fails the run. Re-run the agent for the failing files with the validator message appended to the prompt (there is no automatic retry yet).
- Pilot this brief on two models (a cheaper model and Sonnet) and compare against the validators and the LLM verifier.
