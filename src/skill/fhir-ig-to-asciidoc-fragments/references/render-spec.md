# Deterministic renderer spec (v1, decisions confirmed by the user)

`extract-facts` turns each source JSON into `facts.json`. `render` combines `facts.json` with the agent's `prose.json` and writes the `.adoc`. Everything here is mechanical. The only free text is `keywords` and `summary`.

Decisions marked **[D#]** are choices the user confirmed (listed at the end). Change one only with the user's agreement, because the validators encode them. Values in `{braces}` come from the config file; the examples in this file are from US Core 9.0.0.

## 1. Output format (matches `TopicFragmentWriter`, ike-docs main)

```
// {topicId}
// Topic: {title}
// Type: reference
// Status: published
:topic-id: {topicId}
:topic-type: reference
:topic-status: published
:topic-provenance: external
:topic-citation: {citation}
:topic-license: {license}
:topic-keywords: {ig_slug}, {resource-type-lowercase}, fhir, k1, k2
:topic-summary: {summary}
:topic-canonical: {url}
:topic-resource-type: {resourceType}
:topic-ig-version: {ig_version}
:topic-fhir-version: {fhir_version}

[[{topicId}]]
= {title}

{body}
```

- Line endings `\n`, UTF-8, no BOM, exactly one trailing newline.
- **Not wrapped:** `provenance`, `citation`, `license`, `keywords` (written as-is, keywords joined with `", "`).
- **Wrapped** (via the writer's `attributeLine`): `summary`, `canonical`, `resource-type`, `ig-version`, `fhir-version`.
- Keywords: the three fixed ones (`{ig_slug}`, the lowercased resource type such as `structuredefinition`, `fhir`), then the agent's keywords. The license default is `Fair use summary of copyrighted work — not for redistribution.`
- Wrap algorithm (implemented as `Text.attributeLine` in the tool's source, class `network.ike.docs.fhirfragments.Text`; width 92):
  - Start the line as `:name:`; whitespace in the value is collapsed to single spaces.
  - For each word: if `len(line) + 1 + len(word) > 92` and the line is not blank and does not end with `:`, emit `line + " \"` and start a new line as one space.
  - Then append a space and the word. Continuation lines therefore start with two spaces.
  - A first word is never moved, so an unbreakable value stays inline and may exceed 92 characters.
  - The ` \` suffix does not count toward the width.
- `type` is `reference` and `status` is `published` for every resource **[D12, D13]**.
- Title: `title` from the source; if absent, `name` **[fixed fallback]**.

## 2. TopicId and filename

- TopicId: `{topic_prefix}-{segment}-{id}`, where `topic_prefix` defaults to `ext-standards-{ig_slug}` (for US Core, `ext-standards-us-core-profiles-us-core-patient`) and segment is `profiles`, `extensions`, `searchparams`, `valuesets`, `codesystems`, `capabilitystatements` or `operations`.
- Supported resource types: StructureDefinition, SearchParameter, ValueSet, CodeSystem, CapabilityStatement, OperationDefinition. Any other type in the source folder stops extraction (exit 2) unless the config lists it in `skip_resource_types`.
- Segment for a StructureDefinition: `extensions` if `type == "Extension"` or `kind` is `complex-type` or `primitive-type`; otherwise `profiles`.
- Filename: `{ResourceType}-{id}.adoc` for every file, for example `StructureDefinition-us-core-patient.adoc`, `SearchParameter-us-core-condition-code.adoc` **[D11]**. One flat folder per IG.

## 3. Common rules

- **Fallback text:** a field the format needs but the source lacks renders as `Not specified in source`. Never invent a value.
- **Markdown to AsciiDoc** (description and other text from the source):
  - `**x**` becomes `*x*`; `*x*` and `_x_` become `_x_`; backticks are unchanged.
  - `[text](http…)` becomes `http…[text]`; a relative link keeps only its text **[D9]**.
  - Paragraph breaks and `*` / `-` bullet lists are preserved; `’`-style escapes are decoded.
- **Table cells:** `|` becomes `\|`; newlines become a space; empty cell stays empty.
- **Publishing Metadata (all types):** table `Property | Value` with rows `Publisher`, `Contact`, `Date`, `Canonical URL`. Contact is name plus telecom values joined by `; `. No Jurisdiction, FMM or copyright rows beyond what a type's own Identity table lists **[strict scope]**.
- **Overview (all types):** `== Overview` holding exactly one line, the reference `{topic-summary}`. The summary is written once, in the `:topic-summary:` header attribute, and is the only source of that text. The source description is not printed anywhere in the fragment.

## 4. Body skeletons

Cardinality: use the differential `min`/`max`; where one is omitted use the snapshot value for the same element id and add `min/max from snapshot` to Notes.

### Profile (StructureDefinition, `profiles`)
1. `== Overview`
2. `== Identity and Derivation`: table `Property | Value`: Id, URL, Version, Name, Title, Status, Date, Publisher, FHIR version, Base definition, Kind, Type, Derivation, FMM, Working group, Standards status.
3. `== Conformance Table`: columns `Element | Min | Max | MS | Notes`; one row per differential element in source order. `MS` is `Y` when `mustSupport` is true. Notes are joined with `; `: `slice: {sliceName}`, `type: {code}[ ({profile}, …)]`, the `short` text, and the snapshot tag above **[D1, D2]**.
4. `== Terminology Bindings`: `Element | Strength | Value Set URL`, differential bindings only. If none, the section is omitted.
5. `== Fixed Codes and Pattern Constraints` only if any `pattern[x]` or `fixed[x]` exists: `Element | Constraint | Value` (codings as `system|code`).
6. `== Invariants`: `Key | Severity | Human-readable | FHIRPath Expression`; omitted if none.
7. `== USCDI Extensions` (a US Core convention; other guides simply never produce it) only if any differential element carries `uscdi-requirement = true`: `Element | Slice name | Min | Max | Extension profile URL`; slice and URL cells empty for non-slice elements **[D3]**.
8. `== Publishing Metadata`.

### Extension (StructureDefinition, `extensions`)
`== Overview`; `== Identity`: `URL, Version, Context, FHIR version, FMM, Standards status` (contexts joined with `, ` **[D8]**); `== Extension Structure`: `Path | Type | Binding` for each differential element (binding as `strength: url`, empty if none); `== Publishing Metadata`.

### SearchParameter
`== Overview`; `== Identity`: `Code, Base, Type, Expression, Derived from`; `== Conformance Expectations`: `Parameter | Expectation`: rows `multipleOr`, `multipleAnd`, then one row per comparator code (SHALL / SHOULD / MAY from the expectation extension) **[D6, D10]**; `== Publishing Metadata`.

### ValueSet
`== Overview`; `== Identity`: `URL, Version, Status`; `== Compose`: for each `include`: a heading line with the system (and version if present), then a `Code | Display` table for listed concepts, or a `Property | Operator | Value` table for filters, or a bullet list for imported value sets; an `Exclude` subsection with the same layout when `compose.exclude` exists **[D4]**; `== Publishing Metadata`.

### CodeSystem
`== Overview`; `== Identity`: `URL, Version, Content`; `== Concepts`: `Code | Display | Definition`; `== Publishing Metadata`. The extractor stops with a warning (exit 1) if concepts are nested **[D14]**.

### CapabilityStatement
`== Overview`; `== Identity`: `Kind, FHIR version, Formats`; `== Supported Resources` with one `===` subsection per `rest[].resource[]`: `Interaction | Supported? | Notes` (listed interactions are `Yes`; Notes holds the SHALL/SHOULD/MAY expectation), a search-parameter table `Name | Type | Definition | Notes`, a bullet list of `supportedProfile` URLs, and a `Property | Value` table of any of `versioning`, `readHistory`, `updateCreate`, `conditionalCreate/Read/Update/Delete`, `referencePolicy` that are present **[D5]**; `== Publishing Metadata`.

### OperationDefinition
`== Overview`; `== Identity`: `Kind, Code, Applies to, System, Type, Instance`; `== Parameters`: `Name | Use | Min | Max | Type | Description`; `== Publishing Metadata`.

## 5. Includes file

Header `// IKE-ASCIIDOC-FRAGMENT includes manifest`, `// IG: {ig_dir_name}`, `// Generated: {date}`, then one blank line. For each of `Profiles`, `Extensions`, `Search Parameters`, `Value Sets`, `Code Systems`, `Capability Statements`, `Operations` (skip empty ones): `== {Title}` plus a blank line, then for each file sorted by id `include::{file}[leveloffset=+1]` followed by a blank line.

## 6. `facts.json`

One object per resource: `source_file`, `source_sha256`, `resource_type`, `id`, `seg` (the segment), `topic_id`, `out_file`, `title`, `canonical`, `status`, `description_md`, `purpose_md`, `publishing{…}`, `meta{…}` (the Identity fields), `data{…}` (type-specific, already snapshot-resolved: `rows`, `bindings`, `fixed`, `constraints`, `uscdi`, `contexts` for StructureDefinitions; `code`, `comparators`, … for SearchParameters; and so on), `warnings[]`, and `prose_inputs{…}` (the only part a prose agent reads). Large files (CapabilityStatements, big profiles) are handled here, so agents never read raw source JSON.

## 7. Decisions (confirmed by the user)

- **D1** Conformance Notes composition: slice, type, short, snapshot tag.
- **D2** The old "mark inherited" rule is dropped, because only differential rows appear.
- **D3** USCDI section covers any flagged element, not only extension slices.
- **D4** ValueSet `Exclude` subsection added (one value set needs it to be correct).
- **D5** CapabilityStatement behaviour table and expectation notes included.
- **D6** Comparators shown by code only (no narrative names).
- **D7** (changed again after v2 delivery, per the user) The Overview is the single line `{topic-summary}`, a reference to the agent-written `:topic-summary:` attribute, which is the only source of truth. No description and no lead are printed in it. v1 had an agent-written `overview_lead`; the first v2 printed the converted description.
- **D8** Extension contexts joined in one cell (US Core's jurisdiction extension has 193).
- **D9** Relative links keep text only.
- **D10** SearchParameter with no comparators has no comparator rows.
- **D11** (changed after v2 delivery, per the user) Every file is named `{ResourceType}-{id}.adoc`, not only the ones whose id is shared. v2 first named files by bare id except for ids shared across resource types. The name now shows the type and never collides.
- **D12** `topic-status` is always `published`, even for retired resources.
- **D13** `topic-type` is always `reference`.
- **D14** Nested CodeSystem concepts (and nested OperationDefinition parameters) fail extraction (warning, exit 1) instead of being flattened.
- **D15** Extensions have no Invariants section (strict original brief), so extension constraints such as `us-core-23` on the race extension are not rendered; the coverage check skips constraint keys for extensions.
- **D16** When one table cell holds several URLs (the profile or targetProfile list of a Reference type, in the Notes column of a profile table or the Type column of an extension table), each URL is on its own line using an AsciiDoc hard line break: `type: Reference ( +`, then one URL per line each ending ` +` except the last, which ends ` )`. A single URL stays inline as `Reference (url)`. URLs are never joined with commas.
