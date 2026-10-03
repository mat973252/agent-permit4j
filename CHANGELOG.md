# Changelog

## Unreleased

- Skip compiler-generated bridge methods when registering annotated business tools,
  so a concrete generic override is registered once on ordinary objects and CGLIB
  class proxies. Approval, idempotency and invocation through proxy advice remain enforced.

## 0.5.0 — 2026-09-22

### Breaking Java namespace migration

- Java packages move from `io.github.mat973252.agentpermit` to `io.github.agentpermit4j`.
  Update imports, fully qualified class names, reflection/configuration references and
  recompile consumers; old binaries are not compatible and no compatibility aliases are provided.
- Bundled JDBC SQL and Redis Lua resources move from `io/github/mat973252/agentpermit/`
  to `io/github/agentpermit4j/`. Update application migration resource paths; SQL contents,
  database schemas, approval fingerprints and Redis keys/record formats are unchanged.
- Maven groupId moves from `io.github.mat973252` to the verified `io.github.agentpermit4j`
  namespace. Update all SDK dependency coordinates; no relocation artifacts have been published.
  The GitHub organization is now `agentpermit4j`; the source repository has not been transferred.

### Adoption and candidate preparation (v0.5 iteration)

- A standalone Spring AI consumer registers inventory lookup, preview and reservation
  through public SDK artifacts, with executable approval, tenant, concurrency and audit checks.
- A portable PowerShell verification script builds sources/Javadoc, checks candidate
  artifacts, and verifies a copied consumer online and offline in an isolated Maven repository.
- CI adds real Redis acceptance and retains unsigned candidate artifacts. An independent
  developer trial remains pending; the worksheet records original signatures and assistance.
- Version 0.5.0 is published on Maven Central with sources, Javadoc and GPG signatures.
  A standalone consumer downloaded the public artifacts into an empty Maven repository
  and passed all 10 tests online and offline. The independent developer trial is deferred.

### Business outcome reconciliation (v0.4)

- `ExecutionOutcome` and `ExecutionStatus` distinguish NOT_STARTED, UNKNOWN,
  SUCCEEDED and FAILED independently of pipeline decision outcomes.
- The refund example reserves an operation before payment and reconciles matching
  authoritative evidence without another payment request. Recovery rebuilds services
  over retained simulator state; it does not demonstrate a killed JVM.
- Refund example arguments now include an independent operation reference, amount,
  expected resource version and policy revision. Existing reusable callback APIs remain compatible.

### Reviewed business methods (v0.3)

- `GuardedToolMethods.fromAnnotated` explicitly registers multiple public Spring AI
  methods and executes them inside the guarded idempotency owner.
- Approval services add identified review, application-defined reviewer authorization,
  and decision receipts. Reviewed requests cannot use the legacy approval shortcut.
- JDBC reviewed approval requires the additive review schema. Older binaries reject
  the new states and must not serve reviewed flows.

## v0.2.0 — Git tag, 2026-09-02

Spring AI/Boot/Security integration, JDBC approval/audit, Redis result idempotency,
and a live local Playground. The tag is not evidence of a GitHub Release or a
published Maven dependency. Current source integration is documented in the README.
