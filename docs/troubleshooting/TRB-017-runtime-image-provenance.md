# TRB-017 — Clean Source Without Runtime Image Provenance

## Context

PR #21 records bounded Phase 9 Kubernetes freshness recovery. Original artifacts recorded a clean Git commit and Pod imageIDs independently.

## Symptom

The verifier could accept a clean source fingerprint while running previously built images. Replacement Pods were not fingerprinted. The equal-version verifier also accepted extra Full Snapshots.

## Expected vs Actual

A PASS must connect the captured source to the build image and actual initial/replacement/probe images. Original checks proved neither that connection nor zero additional Full Snapshots throughout recovery.

## Reproduction

Build a local phase8 image from an older commit, switch to a new clean commit, and capture the already-running cluster. Source status alone cannot distinguish that stale image. A checksum-valid result with extra Snapshot receipts previously also passed.

## Impact

Recovery times might be attributed to the wrong implementation. A successful result could overstate the Heartbeat-only protocol path. CI build success did not exercise these evidence assertions.

## Initial Hypothesis

Pod imageIDs and a clean working tree were thought sufficient as separate fingerprints.

## Evidence

The previous source `5938b8dd98f3d51029dc788a251401a0e99c3960` had no revision labels or replacement identity verification. Its capture is superseded and retained in Git history. Revised [EV-P09-K8S-001](../evidence/phase-09/EV-P09-K8S-001/README.md) preserves hashed OCI blobs and initial/post-fault Pod identity.

## Root Cause

The experiment did not verify source-label/build/runtime linkage. kind may expose either a config digest or a multi-image OCI import index as Pod imageID; comparing digest strings alone is insufficient. Normal fixtures also modeled an extra Full Snapshot rather than the intended equal-version Heartbeat path.

## Fix

Build images with revision/tree/clean labels from the clean source after rebuilding artifacts. Match Docker root identity to node-local OCI index/manifest/config content, verify every SHA-256 descriptor edge, and match the actual Pod identity. When kind exposes an import index, verify the import descriptor and its image-name annotation link to the expected built image. Fingerprint both replacement generations and probes, and reject stale images before fault injection. Require a constant Snapshot receipt counter of one throughout each scenario.

## Verification

Context isolation is tested with an unrelated current context. Provenance regressions reject stale source labels, stale replacement imageIDs, altered runtime labels, modified OCI bytes, broken descriptor links, missing replacement Pods and wrong image annotations. Protocol regression rejects unexpected Full Snapshots. CI repeats these regressions and verifies the committed raw bundle against its recorded result. Aggregate negative controls first validate their unmodified complete bundle, preventing a missing unrelated artifact from producing a false-positive rejection.

## Trade-off

Labels are first-party build declarations, not signed builder attestations. The hash graph establishes consistency of those declarations and actual runtime content inside the controlled local fixture. Capturing node-local OCI metadata is specific to kind and does not establish arbitrary registry/builder trust.

## Prevention

Define source-to-build-to-runtime provenance before promoting Evidence. Treat a new Pod generation as a new identity to capture. Retain positive controls when expanding required bundles. Freeze source throughout image build and runtime collection, and use a new artifact directory per run. Keep selected context and namespace explicit for every mutating Kubernetes CLI.

## Related ADR / PR / Commit

PR #21; ADR-009 (LKG / READY_STALE); Phase 8 Helm deployment and Phase 9 evidence methodology. Capture source and verifier result are linked from EV-P09-K8S-001.

## Blog Candidate Summary

A clean Git tree and an image digest are two disconnected facts until the experiment verifies the descriptor graph and source declaration linking them.
