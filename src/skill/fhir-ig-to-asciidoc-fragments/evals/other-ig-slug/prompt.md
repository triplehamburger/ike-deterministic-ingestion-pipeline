---
tags: [fhir-ig-to-asciidoc-fragments]
max_turns: 60
timeout_seconds: 1800
allowed_tools: [Read, Glob, Grep, Skill, Write, Edit, Bash, Agent]
---

Same little package as before in package/ but this one belongs to a different guide: ACME Core IG 1.0.0 (slug acme-core, FHIR 4.0.1). Citation "ACME Standards Body. ACME Core Implementation Guide, v1.0.0. https://example.org/acme/1.0.0/", IG URL https://example.org/acme/1.0.0/, and its canonical namespace is http://hl7.org/fhir/us/core/ (the resources were derived from US Core). Generate the IKE topic fragments and an _includes.adoc into an outputs folder, using acme-core wherever the guide identity belongs, and verify them.

Input files: package/
