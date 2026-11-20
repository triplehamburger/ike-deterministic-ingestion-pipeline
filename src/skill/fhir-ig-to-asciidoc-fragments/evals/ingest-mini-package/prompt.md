---
tags: [fhir-ig-to-asciidoc-fragments]
max_turns: 60
timeout_seconds: 1800
allowed_tools: [Read, Glob, Grep, Skill, Write, Edit, Bash, Agent]
---

I have a small FHIR IG package in the folder package/ (a handful of conformance JSON files plus the usual npm metadata files). Turn the conformance resources into IKE topic fragments (the .adoc format that ike-docs TopicFragmentWriter produces, with an _includes.adoc) and put everything in an outputs folder. The IG is US Core 9.0.0 on FHIR 4.0.1, slug us-core, citation "HL7 International / Cross-Group Projects. US Core Implementation Guide, v9.0.0. http://hl7.org/fhir/us/core/STU9/", IG URL http://hl7.org/fhir/us/core/STU9/, canonical namespace http://hl7.org/fhir/us/core/. Please also tell me how you checked that the output is right.

Input files: package/
