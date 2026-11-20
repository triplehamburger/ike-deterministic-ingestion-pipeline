---
tags: [fhir-ig-to-asciidoc-fragments]
max_turns: 60
timeout_seconds: 1800
allowed_tools: [Read, Glob, Grep, Skill, Write, Edit, Bash, Agent]
---

I generated the IKE topic fragments in fragments/ last week from the source JSON in package/ (US Core 9.0.0, slug us-core, FHIR 4.0.1, citation "HL7 International / Cross-Group Projects. US Core Implementation Guide, v9.0.0. http://hl7.org/fhir/us/core/STU9/", IG URL http://hl7.org/fhir/us/core/STU9/, canonical namespace http://hl7.org/fhir/us/core/). Someone may have touched a few of them by hand since. Can you check they are still correct against the source and tell me exactly which files, if any, are wrong and why? Don't change the fragments themselves.

Input files: package/, fragments/
