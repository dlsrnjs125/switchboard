# Failure-to-Test-to-Evidence Matrix

This matrix is the executable Phase 6 trace from the Phase 0F failure contract to test code and recorded evidence. `PASS` means the declared small local envelope is covered; it does not expand the claim to fleet scale.

| Failure ID | Executable verification | Recovery-complete assertion | Evidence | Status |
| --- | --- | --- | --- | --- |
| `FM-CP-001` | `SwitchboardProviderTest.evaluationRemainsLocalAndUsesLkgAfterDistributionBecomesStale` | Evaluation remains process-local and unchanged without a Control Plane dependency | `EV-P06-REL-001` + `EV-P05-SDK-001` | PASS |
| `FM-PG-001` | `PublicationIntegrationTest.postgresNetworkCutBeforePublishLeavesNoResidueAndRecoveryDoesNotReuseVersion` | Zero pre-commit residue; recovered publish creates complete version 1 exactly once | `EV-P06-REL-001` | PASS |
| `FM-KFK-001` | `KafkaOutageIntegrationTest.committedOutboxSurvivesKafkaPauseAndPublishesAfterBrokerRecovery` | Pending intent survives; recovery produces broker record and `published_at` | `EV-P06-REL-001` | PASS |
| `FM-DST-001` | `GrpcDistributionIntegrationTest.providerKeepsLocalEvaluationDuringDistributionRestartAndConvergesAgain` | `READY → READY_STALE → READY`, version 3 retained, 1,000 evaluations succeed | `EV-P06-REL-001` | PASS |
| `FM-SNP-001` | `SnapshotCoordinatorIntegrationTest.rejectsCorruptAuthoritativeSnapshotWithoutReplacingActiveCache`; `SwitchboardProviderTest.invalidUpdateIsNackedAndPreservesReadyStateAndActiveSnapshot` | Bad candidate never replaces cache/LKG; later valid version converges | `EV-P06-REL-001` | PASS |
| `FM-ORD-001` | `SnapshotCoordinatorIntegrationTest.authenticatesScopeAndReconcilesDuplicateOutOfOrderGapAndConflict`; `KafkaNotificationE2ETest.duplicateDeliveryAndConsumerRestartConvergeMonotonically` | Logical apply count equals unique accepted versions; active version never decreases | `EV-P06-REL-001` | PASS |
| `FM-GAP-001` | `SnapshotCoordinatorIntegrationTest.authenticatesScopeAndReconcilesDuplicateOutOfOrderGapAndConflict`; `SwitchboardProviderTest.heartbeatAheadRequestsFullResyncAndFreshHeartbeatRecoversStaleState` | Full authoritative Snapshot convergence without intermediate merge | `EV-P06-REL-001` | PASS |
| `FM-CRD-001` | `GrpcDistributionIntegrationTest.establishedStreamClosesWithinRevalidationWhenCredentialIsRevoked`; `rotatedCredentialRestoresOnlyTheOriginalApplicationScope` | Old stream closes/auth fails; rotated secret restores only original scope | `EV-P06-REL-001` | PASS |
| `FM-TEN-001` | Phase 1/3/4 tenant and credential negative suites | No cross-tenant read, stream, mutation, Snapshot, audit, or outbox side effect | `EV-P01-TEN-001`, `EV-P04-DST-001`, `EV-P06-REL-001` | PASS |
| `FM-SDK-001` | `SwitchboardProviderTest.restartBootstrapsFromDurableLkgWhileRemoteIsUnavailable`; `DiskLkgStoreTest.atomicReplacementSurvivesRestartAndIgnoresInterruptedTemporaryArtifact` | Valid LKG boots stale; corrupt/expired cache is rejected; replacement survives restart | `EV-P06-REL-001` | PASS |
| `FM-RCN-001` | `ReconnectBackoffTest`; `GrpcDistributionIntegrationTest.concurrentSessionAdmissionIsBoundedBeforeSnapshotLoading`; `SnapshotDistributionGrpcServiceTest.rejectedReconnectIsAdmissionControlledBeforeAuthoritativeSnapshotLoad` | Delays are bounded/jittered; excess sessions fail before DB Snapshot load | `EV-P06-REL-001` | PASS for safety envelope; Phase 9 owns fleet capacity |
| `FM-BKP-001` | `ClientSessionTest.slowClientKeepsOnlyLatestFullSnapshot` | One pending full Snapshot is coalesced to the newest version; healthy sessions are independent | `EV-P06-REL-001` | PASS for queue bound; Phase 9 owns load envelope |
| Outbox crash boundary | `PublicationIntegrationTest.expiredOutboxLeaseIsReclaimedAndOnlyCurrentClaimCanCompleteDelivery` | Expired claim is reclaimed; stale claim cannot persist completion | `EV-P06-REL-001` | PASS |
| Consumer restart boundary | `KafkaNotificationE2ETest.duplicateDeliveryAndConsumerRestartConvergeMonotonically` | Restarted consumer converges cache from version 4 to 6 without duplicate logical apply | `EV-P06-REL-001` | PASS |

## Gate rule

A Phase 6 rerun is valid only when fault cleanup succeeds. Test failure, leaked pause/cut state, missing result XML, or a recovery assertion that checks process health without version/checksum convergence makes the run `FAIL` or `INVALID` rather than `PASS`.
