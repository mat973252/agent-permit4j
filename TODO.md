# AgentPermit4j delivery backlog

The backlog is ordered by proof of value. Each item includes an observable acceptance check.

## Package naming migration (planned 2026-09-19)

Ownership check and execution order: [docs/iterations/package-namespace.md](docs/iterations/package-namespace.md).

- [x] Review and migrate the public Java package namespace away from the personal `io.github.mat973252.agentpermit` prefix before a stable release.
  - Migrated Java packages and Maven groupId to `io.github.agentpermit4j`. GitHub organization ownership and Central Portal namespace verification completed on 2026-09-22; namespace verification does not mean artifacts are published.
  - Verify: update source/test packages, imports, reflection/configuration references, examples and documentation consistently; document source/binary compatibility impact and pass the full Maven verification. Keep this migration separate from Redis validation changes.

## Repository bootstrap

- [x] Establish the initial six-module Maven structure and Java 21 baseline.
- [x] Document the framework boundary and unified demo flow.
- [x] Make the GitHub Actions build green on the public repository.

## P0 — trusted execution loop (v0.1)

- [x] Define `Principal`, `Action`, `Resource`, `InvocationContext`, `ToolDescriptor`, and `ToolInvocation` in `agent-permit-core`.
  - Verify: the same model represents file, SQL, messaging, HTTP, and deployment calls without business-specific types.
- [x] Define static tool metadata (`effect`, `reversibility`, `dataSensitivity`) and a dynamic `RiskEvaluator` SPI.
  - Verify: SQL fixtures classify `SELECT` as LOW, selective `UPDATE` as HIGH, unbounded `UPDATE` as CRITICAL, and `DROP`/`TRUNCATE` as DENY.
- [x] Implement the deterministic decision pipeline: validate → normalize → authorize → assess risk → execute / request approval / deny.
  - Verify: every terminal path returns a structured reason and emits audit events.
- [x] Implement Java policy SPI and protected-resource policies.
  - Verify: reading `README.md` is allowed and recursive deletion of `/workspace` is denied with `PROTECTED_PATH`.
- [x] Implement approval requests with expiry and normalized-argument fingerprint binding.
  - Verify: changing service or version after approval invalidates the approval and causes zero executions.
- [x] Implement in-memory idempotency for the first vertical slice.
  - Verify: concurrent invocations with one idempotency key produce exactly one mock side effect.
- [x] Implement append-only in-memory audit and a replay-safe event view.
  - Verify: a complete timeline shows policy, risk, approval, execution, and result events without re-running side effects.
- [x] Ship three reproducible Playground scenarios: file read/delete, SQL read/write, staging/production deployment.
  - Verify: one command starts the demo and all scenarios run without real external systems.

## P1 — usable Spring integration (v0.2)

- [x] Add Spring AI tool interception and context mapping.
  - [x] Prove a Spring AI 2.0 `ToolCallback` sample that maps trusted `ToolContext` metadata and routes every side effect through `DecisionPipeline`.
  - [x] Add a separate result-bearing execution contract that returns idempotent tool output without adding it to audit decisions.
  - [x] Extract a reusable adapter after result-bearing execution semantics stabilize.
  - [x] Add a minimal `@AgentPermit` policy shortcut for Spring AI `@Tool` methods without scanning or bypassing the pipeline executor.
- [x] Add Spring Boot auto-configuration and starter modules only after the core API stabilizes.
- [x] Add JDBC approval/audit storage and Redis idempotency adapters.
  - [x] Add JDBC approval request storage with persisted fingerprint and expiry verification.
  - [x] Add append-only JDBC audit timeline storage.
  - [x] Add Redis result idempotency with cross-process concurrency acceptance tests.
- [x] Add messaging and HTTP mock tools with external-domain, SSRF, method, and payload policies.
  - [x] Add runtime evaluator routing plus configurable HTTP host, SSRF, method, and payload policies with Playground cases.
  - [x] Add messaging mock tools and destination/content policies.
- [x] Implement the Playground web UI: conversation, execution timeline, approval detail, audit, policy explanation, and replay view.
  - [x] Add an offline static wireframe with synthetic fixture JSON and clickable approval/replay views.
  - [x] Connect the UI to live decision, approval, audit, and replay APIs.
- [x] Add Spring Security principal resolution and tenant/environment propagation.

Acceptance: `PersistedSpringAiAcceptanceTest` proves that a Spring AI tool can pause for
persisted approval, resume once, and expose a complete persisted audit timeline.

## P2 — trusted business writes (v0.3)

Iteration plan and evidence: [docs/iterations/v0.3.md](docs/iterations/v0.3.md).

- [x] Ship a local order-refund example with a JDBC business ledger and a controllable payment simulator.
  - Verify: a refund changes the balance and version; denied and unapproved calls change neither the ledger nor payments.
- [x] Provide explicit registration of multiple annotated business methods inside the guarded executor.
  - Verify: normalized arguments reach the original method, duplicate names fail at registration, and retries do not invoke it again.
- [x] Bind reviewed approvals to an authorized approver and retain the decision identity and time.
  - Verify: unauthorized, cross-tenant, and self approval are rejected; a reviewed request cannot use the legacy approval shortcut.
- [x] Show a backend-built refund preview and bind execution to its amount, resource version, and policy revision.
  - Verify: changing arguments or policy invalidates approval; a concurrent order update is caught by a conditional database write before payment.
- [x] Document a reproducible adoption walkthrough and complete the v0.3 acceptance matrix.
  - Verify: three business tools run locally without credentials or real payments; Maven Wrapper verify passes.

## P3 — outcome reconciliation (v0.4)

Plan and failure matrix: [docs/iterations/v0.4.md](docs/iterations/v0.4.md).

- [x] Distinguish unknown outcomes from known failures and expose a safe execution reference.
  - Verify: the preview reference is independent of approval and pipeline idempotency; outcome views contain only reference/status/reason and require the original trusted owner/context.
- [x] Query the downstream operation before resolving an uncertain execution; never automatically replay an uncertain write.
  - Verify: authoritative matching evidence updates the balance and outcome atomically; missing, unavailable, or mismatched evidence retains UNKNOWN and the order reservation.
- [x] Prove payment-success/receipt-loss recovery with deterministic failure injection.
  - Verify: lost response and failures before/after settlement commit recover with one payment request; independent service instances and concurrent reconciliation cannot duplicate payment or balance updates.

## P4 — independent adoption and release readiness (v0.5, in progress)

Iteration plan: [docs/iterations/v0.5.md](docs/iterations/v0.5.md).

- [x] Build a standalone Maven consumer of three guarded Spring AI methods.
  - Verify: resolve SDK artifacts without the SDK parent POM, Playground dependencies, or reactor source access; prove approval and same-key retry behavior in an isolated consumer build.

- [ ] Have an independent developer integrate three existing Spring AI methods using the walkthrough.
  - Deferred by the maintainer on 2026-09-22; no longer a gate for 0.5.0. Keep the trial unmeasured and use full verification, real Redis and isolated consumer checks as the release gate.
  - Verify: record original signatures, integration time, extra wiring, maintainer assistance, and unsupported signatures; automated consumer tests do not replace this unmeasured adoption check.
- [ ] Use that evidence to fix at most one observed adoption obstacle.
  - Verify: a failing consumer example demonstrates the obstacle, and the smallest documentation, wiring, DTO, or proxy change resolves it while preserving security contracts.
- [x] Prepare unsigned candidate artifacts and verify local artifact consumption.
  - Verify: the candidate profile produces 41 library files, the isolated consumer passes online and offline, and release/adoption instructions distinguish completed checks from pending publication.
- [x] Complete signed publication and public-repository consumption after maintainer validation (independent adoption deferred on 2026-09-22).
  - Completed on 2026-09-22: Central published 0.5.0 and an empty-cache public consumer passed online and offline. See [release evidence](docs/iterations/v0.5-release.md). The 2026-10-03 maintenance consumer also verifies changed arguments, trusted identities and controlled same-key overlap; this is not independent adoption.
  - Verify: assign the release version, validate signing and Portal access, confirm the private reporting channel, publish, and resolve the released artifacts from an empty Maven repository without source install.
- [x] Complete automated candidate regression evidence, including the opt-in real Redis suite.
  - Verify: full Maven Wrapper verify, the standalone consumer, and five real Redis acceptance cases pass. Actual adopter results remain unmeasured; publication is recorded separately above.

## Later — production hardening and ecosystem

- [ ] OpenTelemetry metrics and traces.
- [ ] OPA policy adapter.
- [ ] Webhook, Slack, and Feishu approval providers.
- [ ] Kafka audit sink.
- [ ] MCP and LangChain4j adapters.
- [ ] Policy versioning, signed decisions, retention controls, and threat-model review.

## Explicit non-goals for v0.1

- A general-purpose policy language.
- Real production deployment, email, or database connectors.
- Multiple agent-framework integrations.
- A full identity platform or workflow engine.
- A polished multi-page admin product before the execution loop is proven.
