# Architecture boundary

AgentPermit4j owns the deterministic boundary between a proposed tool call and an external side effect.

```text
model proposes tool call
        |
        v
schema validation -> argument normalization -> authorization -> risk evaluation
                                                             |
                               +-----------------------------+------------------+
                               |                             |                  |
                            execute                       approval             deny
                               |                             |                  |
                               +---------- idempotency ------+------------------+
                                                  |
                                                audit
```

## Core invariants

1. Model text never grants permission.
2. Approval is bound to the normalized invocation fingerprint.
3. A policy decision is explainable by stable reason codes.
4. Retries cannot duplicate a side effect.
5. Audit events describe decisions and outcomes without exposing secrets.
6. Sample tools remain outside the reusable core.

## Context model

A decision is based on `subject + action + resource + context`. Risk is not a constant attached to a tool name; it also depends on normalized arguments, principal attributes, resource attributes, tenant, and environment.

The bootstrap domain model lives in `agent-permit-core` and uses the following immutable values:

- `Principal(id, attributes)` identifies the requesting subject without carrying credentials.
- `Action(name)` names the requested effect independently of a business-specific tool class.
- `Resource(type, identifier, attributes)` identifies the target across file, SQL, messaging, HTTP, and deployment calls.
- `InvocationContext(tenantId, environment)` carries the minimum decision context for the first vertical slice.
- `ToolDescriptor(name, effect, reversibility, dataSensitivity)` identifies the tool contract and its static risk metadata.
- `ToolInvocation` combines the descriptor, principal, action, resource, context, and scalar arguments.
- `RiskAssessment(level, reasonCode)` carries the dynamic risk result with a stable machine-readable reason.
- `GateDecision(permitted, reasonCode)` represents validation and authorization gates.
- `DecisionResult(outcome, reasonCode)` represents an executed, approval-required, denied, or failed terminal outcome.

Attribute and argument maps are copied on construction and exposed as immutable maps so authorization and risk inputs cannot change during a decision.

## Dynamic risk evaluation

`agent-permit-policy` defines the `RiskEvaluator` SPI. The SQL evaluator parses statements into an AST and classifies read-only, selective update, unbounded update, and destructive statements. Missing input, parse failures, multiple statements, non-SQL resources, and unsupported statement types fail closed with `DENY`.

An update with a predicate is classified as `HIGH`, while a missing predicate or literal tautology such as `1 = 1` is `CRITICAL`. `HIGH` means that a syntactic predicate exists; it does not prove a small affected-row count or semantic safety. Later pipeline stages must still apply policy and approval rules.

`RiskEvaluatorRegistry` snapshots a runtime-provided map from the exact resource type in the normalized invocation to an evaluator. It delegates without changing successful assessments and denies unregistered resource types with `RISK_EVALUATOR_UNAVAILABLE`. A registered evaluator that returns no assessment or throws a runtime exception fails closed with `RISK_EVALUATION_FAILED`; exception details are not exposed. This keeps routing explicit and lets Spring or another configuration layer build the map without adding framework types to the policy API.

`HttpRiskEvaluator` evaluates an HTTP invocation whose resource identifier is the target URI and whose scalar arguments contain `method` and optional `payload`. `HttpRiskPolicy` is an immutable runtime configuration snapshot containing exact allowed HTTPS hosts and a maximum UTF-8 payload size. GET/HEAD are `LOW`, POST/PUT/PATCH are `HIGH`, and DELETE is `CRITICAL`; unsupported methods, read payloads, oversized payloads, malformed targets, non-default ports, non-HTTPS targets, unlisted hosts, localhost, and IP literals are denied with stable reason codes.

The HTTP evaluator is deterministic and does not perform DNS resolution. Exact host allowlisting reduces the target surface, but a real HTTP executor must still resolve the host and reject private, loopback, link-local, and other forbidden addresses immediately before connecting to protect against DNS rebinding and time-of-check/time-of-use changes.

`MessagingRiskEvaluator` evaluates `message.send` invocations whose resource type is `messaging`, resource identifier is the destination, and scalar `body` argument is the message content. `MessagingRiskPolicy` takes an immutable snapshot of exact allowed destination identifiers and a maximum UTF-8 body size. A missing or blank body, oversized body, unlisted destination, different action, or disguised resource type is denied with a stable reason code. An allowed send is always `HIGH` and therefore requires backend approval before the mock or real executor runs.

Destination identifiers are opaque and matched exactly; the reusable policy does not guess vendor-specific Slack, Feishu, webhook, channel, or tenant normalization rules. Text inside the body cannot grant approval, even when it claims that an administrator approved the send. Semantic moderation, DLP, and tenant-specific destination ownership remain application authorizer responsibilities rather than a brittle keyword blacklist.

## Deterministic decision pipeline

`agent-permit-execution` owns the fixed orchestration order: validate, normalize, authorize, assess risk, then select one terminal path. Validation and authorization failures stop immediately. `LOW` risk executes once, `HIGH` and `CRITICAL` return `APPROVAL_REQUIRED` without execution, and `DENY` remains denied. An executor exception is converted to the generic `EXECUTION_FAILED` reason so implementation details and secrets are not exposed.

Every terminal result emits a minimal `DecisionAuditEvent` containing tool, principal, tenant, outcome, and stable reason code. Raw invocation arguments are excluded. Append-only timelines, approval fingerprint binding, and idempotent side-effect protection are supplied through separate components rather than embedded in orchestration.

The pipeline depends on the `RiskEvaluator` interface rather than tool-specific code. SQL and HTTP evaluators are selected through `RiskEvaluatorRegistry` without changing pipeline control flow.

`DecisionPipeline` keeps the original void `ToolExecutor` contract. `ResultDecisionPipeline` is a parallel, framework-neutral path for tools that return immutable string output such as serialized API responses. Both pipelines use the same package-internal `DecisionPreflight`, so validation, normalization, authorization, risk, approval, and audit ordering cannot drift. `ToolExecutionResult` keeps the business output separate from `DecisionResult`; output is required only for `EXECUTED` and forbidden for denied, approval-required, or failed decisions.

## Business outcome values (v0.4)

The execution module adds `ExecutionOutcome(reference, status, reasonCode)` and
`ExecutionStatus` without changing decision enums, cached-result serialization,
or existing exception behavior. NOT_STARTED means no attempt has been claimed;
UNKNOWN means authoritative completion is not established locally; SUCCEEDED and
FAILED describe conclusive business outcomes. A tool that returns this value has
completed its guarded method call, even when its business outcome is UNKNOWN.
Clients must inspect the business status rather than infer success from EXECUTED.

The reference is opaque and distinct from approval IDs and pipeline idempotency
keys. It grants no read or execution authority. The value contains no command,
credentials, fingerprint, or raw exception text. Storage, downstream queries,
authorization, and reconciliation remain application responsibilities. The refund
example below is their current concrete implementation; no generic workflow or
reconciliation repository has been added to the reusable modules.

## Protected file resources

`ProtectedPathAuthorizer` implements the Java `Authorizer` SPI for the first protected-resource policy. It allows normal reads such as `/workspace/README.md`, but denies deletion of `/workspace` and recursive deletion of its descendants with `PROTECTED_PATH`. A file action paired with a non-file resource is denied to prevent type-disguise bypasses.

Path matching is a deterministic, filesystem-free lexical check. It treats slash and backslash as separators, compares path segments rather than string prefixes, and fails closed for relative paths, traversal, URI syntax, drive-letter paths, UNC paths, control characters, and ambiguous recursive flags. This avoids host-dependent behavior in policy tests. Symlinks, junctions, mount points, ACLs, and time-of-check/time-of-use protection require a filesystem-aware executor check in a later adapter; the lexical policy does not claim to resolve them.

## Approval binding and expiry

`agent-permit-approval` fingerprints the complete normalized `ToolInvocation`, including tool metadata, principal and resource attributes, action, tenant, environment, and arguments. The versioned SHA-256 canonical encoding uses explicit field names, UTF-8 byte lengths, and byte-sorted map keys; it does not rely on record, map, or JSON string rendering. Approval records expose only the lowercase digest rather than canonical argument bytes.

`InMemoryApprovalService` creates expiring requests, records approval, and verifies a request ID against the normalized invocation supplied by the pipeline. A request is invalid when `now >= expiresAt`. Unknown, pending, expired, or fingerprint-mismatched requests fail closed with stable reason codes. Changing a deployment resource identifier (service) or its `version` argument therefore requires a new approval.

For `HIGH` and `CRITICAL` risk, `DecisionPipeline.process(invocation, approvalRequestId)` executes only after a valid approval. Missing or invalid approval returns `APPROVAL_REQUIRED` with zero executions. The original `process(invocation)` behavior remains compatible and never implicitly grants approval. Persistent approval consumption is provided only when the approved result-bearing path also receives a stable idempotency key and the Redis guard described below.

## JDBC approval adapter

`agent-permit-jdbc` provides `JdbcApprovalService`, a framework-neutral implementation of the existing `ApprovalVerifier`. Its production dependencies point only to core and approval; `DataSource`, `Clock`, request ID generation, and `InvocationFingerprinter` are explicit constructor inputs. The Spring Boot starter does not discover a database or create this service implicitly.

The bundled schema stores only the request ID, lowercase fingerprint digest, expiry as UTC epoch milliseconds, and numeric approval state. Expiry is rounded down to millisecond precision when the request is created. It does not persist canonical fingerprint bytes, normalized arguments, principal attributes, approval secrets, or database exception text. Applications apply the schema explicitly with their migration system; the adapter performs no implicit DDL.

Request creation uses a parameterized insert. Because its return type is `ApprovalRequest` rather than a decision, a write failure throws a generic `IllegalStateException` without the driver exception as its cause. Approval uses a conditional update that succeeds only for an existing pending request before its expiry, making concurrent approval idempotent across service instances. Verification reloads the record, checks the injected application clock with the same `now >= expiresAt` boundary as the in-memory service, then checks pending state and the complete invocation fingerprint. Approval and verification connection, SQL, missing-schema, and corrupt-state failures deny with the stable `APPROVAL_STORAGE_UNAVAILABLE` reason instead of granting approval or exposing driver details.

This adapter persists verification state but deliberately does not consume approval in JDBC. The pipeline verifies approval before claiming idempotency; consuming there would reject a legitimate same-key cached retry. For an approved result-bearing call, `DecisionPreflight` therefore passes the verified approval request ID to the Redis guard, which binds it atomically with the result idempotency claim described below.

## Identified review requests (v0.3)

Both approval services add `requestReview`, identified `approve`, and a read-only
`decision` lookup. An application-provided `ApprovalAuthorizer` sees the trusted
reviewer and the exact normalized invocation. The service verifies fingerprint
and expiry before recording approval. Reviewer policy failure returns a stable
denial; the original constructor denies identified approvals unless configured.
The legacy `approve(requestId)` path cannot approve a reviewed request.

In JDBC, the original numeric state has four defined values: 0 legacy pending,
1 legacy approved, 2 review pending, and 3 reviewed approved. Other values remain
invalid. The additive approval-review schema stores request ID, approver ID,
tenant ID, and decision time. A conditional state update and receipt insert share
one JDBC transaction. Existing legacy requests do not require the new table;
reviewed approval writes fail closed if it is absent. Older binaries fail closed
when they encounter the new states and must not be used to serve reviewed flows.

The application owns reviewer authentication, role/tenant policy, review endpoint
authorization, and safe before/after display. The services retain no raw proposal
arguments. A decision lookup does not authenticate its caller or expose an
execution capability. The in-memory service retains the first successful review
under its existing monitor; JDBC uses its conditional update across instances.

## In-memory idempotency

`DecisionPipeline.process(invocation, approvalRequestId, idempotencyKey)` applies idempotency after normalization, authorization, and any required approval, immediately before the executor. The key is transport metadata and is deliberately excluded from `ToolInvocation` and approval fingerprints. Existing overloads remain compatible and execute without idempotency; callers that require retry protection must provide a stable non-blank key.

`InMemoryIdempotencyGuard` atomically binds the key to the normalized invocation fingerprint. Concurrent callers with the same key and fingerprint share one `CompletableFuture<DecisionResult>` and therefore one executor call. Both `EXECUTED` and `FAILED / EXECUTION_FAILED` results remain cached because an executor exception can leave the external side effect in an unknown state. Reusing a key with another fingerprint returns `DENIED / IDEMPOTENCY_INVOCATION_MISMATCH` without a new side effect.

`InMemoryResultIdempotencyGuard` applies the same coordination to the complete `ToolExecutionResult`. Concurrent and later retries therefore receive the exact cached output without re-running the tool. Failed results remain cached without output, and a fingerprint mismatch never exposes the output stored under the reused key. Its approval-aware overload also binds one approval request to one idempotency key and fingerprint inside that guard instance: the same pair may retry, another key receives `APPROVAL_ALREADY_CONSUMED`, and another fingerprint receives `IDEMPOTENCY_INVOCATION_MISMATCH`. The void and result guards share one package-internal coordinator rather than duplicating concurrency logic.

This guarantee is scoped to one guard instance in one process. Entries are not persisted or evicted, and the implementation does not claim cross-process exactly-once delivery. Each request still emits its own terminal decision audit timeline as described below.

## Redis result idempotency and approval consumption

`agent-permit-redis` implements the existing `ResultIdempotencyGuard` contract without adding Redis types to core, approval, or execution. The application supplies and owns a Jedis `UnifiedJedis` client plus an immutable key prefix, owner lease, and waiter polling interval. The public three-argument guard method remains compatible; execution adds a default four-argument overload so `ResultDecisionPipeline` can pass the approval request ID only after successful `HIGH` or `CRITICAL` verification. A legacy implementation that does not override that approval-aware method fails closed with `FAILED / APPROVAL_CONSUMPTION_UNAVAILABLE` and does not invoke its side effect.

Immediately before the external side effect, one Redis Lua claim atomically binds a SHA-256 digest of the idempotency key to the complete normalized invocation fingerprint and elects one owner. Same-key, same-fingerprint callers wait for or reuse the stored terminal `ToolExecutionResult`, including its exact string output. Both successful and failed results remain stored. A fingerprint mismatch returns `DENIED / IDEMPOTENCY_INVOCATION_MISMATCH` without exposing cached output. Failure to claim Redis fails closed with zero newly authorized side effects and `FAILED / IDEMPOTENCY_STORAGE_UNAVAILABLE`.

For an approved result call, the same script also binds a digest of the approval request ID to the idempotency-key digest and fingerprint. The original pair can reach the same cached result; a different key or fingerprint returns `APPROVAL_REQUIRED / APPROVAL_ALREADY_CONSUMED`. If the approval binding survives but its result entry is missing, the request fails with `IDEMPOTENCY_STATE_LOST` instead of recreating execution authority. Calls using a pipeline overload without an idempotency key do not enter this boundary and therefore receive neither Redis approval consumption nor cross-process deduplication. The Spring AI adapter requires a stable key from trusted transport context.

The owner lease detects an abandoned execution but is not a transferable lock. Once expired, either claim or completion permanently changes the record to `FAILED / IDEMPOTENCY_OWNER_LOST`; no waiter becomes a new owner. A late original owner receives that same terminal result. The adapter does not renew a live lease, so applications must configure it above the longest expected tool execution time. This chooses at-most-once execution over availability because an interrupted process may have completed the external side effect before losing Redis completion state. The external system and Redis are not one transaction, so the adapter does not claim unconditional exactly-once delivery.

Result entries and approval bindings deliberately have no TTL, eviction path, or public delete API. Automatic expiry would eventually allow the same key to execute again and violate the retry invariant. Production deployments must protect cached output as sensitive data and use persistent, access-controlled Redis with an appropriate high-availability design and `noeviction`; data loss, flush, manual deletion, or eviction destroys the guarantee. All adapter keys currently share the `{execution}` Redis Cluster hash slot so the multi-key approval claim remains atomic, which is an explicit single-slot scaling limit. Retention and partitioning require a future design that does not silently recreate execution authority.

## Append-only audit timeline

`InMemoryAuditLog` is an `AuditSink` implementation that only appends and returns detached immutable snapshots. A pipeline run receives an opaque timeline ID and strictly increasing per-timeline sequence numbers. Events contain only tool, principal, tenant, stage, status, stable reason code, and the terminal `DecisionResult`; raw invocation arguments, canonical fingerprint bytes, exception messages, approval IDs, and idempotency keys are excluded.

An approved high-risk path records `POLICY → RISK → APPROVAL → EXECUTION → RESULT`. A low-risk path records approval as `NOT_REQUIRED`; an early denial records only stages that actually occurred plus `RESULT`. The existing functional `AuditSink.record(DecisionAuditEvent)` contract remains compatible: legacy sinks receive the terminal result, while timeline-aware sinks override the stage-event method.

`replaySafeView(timelineId)` filters and orders already-recorded events into an immutable `ReplaySafeAuditView`. It does not receive or invoke a pipeline, policy, approval service, idempotency guard, or executor, so viewing the timeline cannot repeat a side effect. The in-memory implementation remains process-local; retention, signatures, and cross-process transport remain outside the current scope.

## JDBC audit adapter

`agent-permit-jdbc` provides `JdbcAuditLog`, a framework-neutral implementation of `AuditSink`. Its production dependencies point only to core and audit, and both `DataSource` and legacy timeline ID generation are explicit constructor inputs. Applications apply the bundled schema with their migration system; the adapter performs no implicit DDL.

The schema persists exactly the safe fields exposed by `AuditEvent`: timeline ID, caller-assigned sequence, stage, tool, principal, tenant, status, stable reason code, and optional terminal outcome. It does not persist raw arguments, tool output, approval IDs, idempotency keys, fingerprints, exception messages, or canonical invocation bytes. Pipeline sequence allocation remains exclusively owned by `InvocationAuditTrail`; the JDBC adapter does not introduce a second counter. Its `(timeline_id, event_sequence)` primary key rejects a duplicate append instead of overwriting an event, and replay selects one timeline ordered by sequence before constructing an immutable `ReplaySafeAuditView`.

Writes are synchronous and append-only; the adapter exposes no update or delete API. SQL failures and corrupt stored events become a generic `IllegalStateException("audit storage unavailable")` without driver details. The pipeline does not silently swallow that failure. Because an `EXECUTION` audit write can occur after an external side effect, audit persistence alone does not make retries exactly once; callers that need cross-process retry safety must configure the Redis result-idempotency boundary and provide a stable key.

## Spring AI adapter

`agent-permit-spring-ai` is a reusable adapter module that depends on `agent-permit-core`, `agent-permit-execution`, and Spring AI's model API. No framework dependency flows back into core, policy, approval, audit, or execution. `agent-permit-playground` consumes the adapter for end-to-end acceptance coverage rather than owning the implementation.

The public surface is deliberately small. Applications construct `GuardedToolCallback` from a Spring AI `ToolDefinition`, a long-lived `ResultDecisionPipeline`, and an immutable `SpringAiToolContract`; construction rejects a definition name that differs from the contract descriptor name. As a convenience, an application may place `@AgentPermit` beside Spring AI's `@Tool` and construct the callback from the configured pipeline dependencies plus the annotated `Method`. That path derives the definition and contract but still uses the pipeline executor for the side effect. It performs no method invocation or classpath scan. `SpringAiToolContextKeys` publishes the five transport key names. JSON mapping and mapping exceptions remain package-internal so callers cannot bypass or partially reassemble the trusted mapping path.

`@AgentPermit` carries only trusted code metadata and common fixed constraints. Its conservative defaults describe an HTTP write to argument `uri` with HIGH risk, irreversible behavior, and restricted data; callers write only overrides plus optional environment, host, method, and UTF-8 payload-size limits. The annotation authorizer runs after the application authorizer, while the annotation risk floor is combined with the dynamic evaluator by taking the stricter result. Dynamic evaluators therefore remain the extension point for argument-dependent SQL, HTTP, API, middleware, or domain rules. HTTP-only fields on another resource type are rejected at construction.

An annotation may optionally replace its denial reason with an uppercase machine code and add a static display message. Application authorizer decisions and all other pipeline stages keep their original reasons. Message lookup uses the terminal denial code. Construction rejects the reserved built-in `ANNOTATION_*` prefix; the application remains responsible for choosing a custom code that does not collide with another policy reason for the same tool. The custom code becomes the `DecisionResult` reason and is audited; the display message is added only to the Spring callback response envelope and never enters `DecisionResult`, idempotency storage, or audit events.

`GuardedToolCallback` is the registered Spring tool and routes every call through the injected pipeline; it never invokes another callback after the decision. Keeping the external side effect inside the pipeline preserves approval, idempotency, and audit semantics.

`SpringAiInvocationMapper` accepts a flat JSON object of scalar arguments. Principal, tenant, environment, optional approval request ID, and required idempotency key come only from Spring AI `ToolContext`, which is application-controlled transport metadata not supplied to the model. Copying arbitrary HTTP request, user, or model fields into that trusted context violates the adapter contract. Missing trusted context, invalid JSON, nested values, and missing resource identifiers fail closed with stable Spring mapping reason codes and zero side effects. Unexpected pipeline exceptions are reduced to `FAILED / SPRING_AI_PIPELINE_FAILED`; exception messages and raw input are not returned.

The callback uses `ResultDecisionPipeline`. Every response contains `outcome` and `reasonCode`; an `EXECUTED` response also contains the string `output` returned by the tool. JSON serialization escapes the output instead of concatenating raw content. Output is cached for in-memory idempotent retries but is never passed to the audit sink.

The adapter performs no component scanning, property binding, bean discovery, identity resolution, or security-context access. It exposes an optional `TrustedToolContextResolver` hook so a framework integration can replace trusted transport metadata immediately before mapping; resolver failure remains fail-closed through the existing context-invalid decision.

## Explicit business-method registration (v0.3)

`GuardedToolMethods.fromAnnotated` builds an immutable, name-ordered list from
explicit application objects. All public methods it discovers with `@Tool` must
also have `@AgentPermit`. Missing parameter names, duplicate tool names, and an
empty registration are rejected. For a Spring CGLIB class proxy, discovery reads
the user class's public method annotations and parameter metadata; invocation
still targets the supplied proxy, preserving its advice inside the guarded
executor. Final annotated methods on class proxies are rejected because CGLIB
cannot advise them; final methods on ordinary objects remain supported.
It never unwraps the target object to bypass advice. The factory does
not perform bean or classpath scanning, interface annotation discovery, JDK
interface-proxy adaptation, or automatic replacement of existing callbacks.

Its shared dependencies omit the executor: each method supplies its own executor
inside `ResultDecisionPipeline`. Spring AI's `MethodToolCallback` is invoked only
by the elected idempotency owner, with JSON serialized from normalized scalar
arguments. Business methods receive a fresh minimal `ToolContext` containing the
normalized principal ID, tenant ID, and environment; no ambient context or raw
model input is captured. The original annotation-only factories remain compatible
and continue using their explicitly injected executors.

## Spring Boot convenience modules

`agent-permit-spring-boot-starter` depends on `agent-permit-spring-boot-autoconfigure`, which depends on the reusable Spring AI adapter and Spring Boot auto-configuration API, with Spring Security Core as an optional integration dependency. This preserves the dependency direction `starter → autoconfigure → spring-ai → execution/core`; no Spring Boot or Spring Security dependency flows into the framework-neutral modules.

Boot discovers the Spring AI and optional Spring Security auto-configurations through `AutoConfiguration.imports`. The Spring AI configuration creates a `GuardedToolCallback` only from one application-provided `ToolDefinition`, one `SpringAiToolContract`, and one fully configured `ResultDecisionPipeline`. Missing inputs cause the configuration to back off, an application-provided `GuardedToolCallback` wins, and ambiguous inputs fail Spring injection rather than being selected silently.

The optional security configuration activates only when Spring Security is present and the application provides one `SpringSecurityTenantEnvironmentResolver`. At callback time it snapshots the authenticated, non-anonymous principal from `SecurityContextHolder`, resolves tenant and environment through that explicit application boundary, and overrides all three supplied identity fields while retaining approval and idempotency metadata. Missing authentication, blank principal, resolver failure, or invalid tenant/environment returns an invalid trusted context and prevents execution. It never infers tenant or environment, copies no authorities automatically, and does not propagate thread-local or Reactor security state; applications that cross threads must use Spring Security's context-propagation facilities.

The convenience modules still define no policies, executors, approval services, permissive property defaults, persistence, or component scanning. Auto-configuration therefore shortens explicit wiring without weakening the adapter's fail-closed trust boundary.

## Reproducible Playground

`agent-permit-playground` assembles the real pipeline against in-memory counters and logs. Its file cases read `/workspace/README.md` and deny recursive deletion of `/workspace`; its SQL cases execute a `SELECT`, pause a selective `UPDATE`, then execute the exact approved update; its HTTP cases allow an external read, require approval for a write, and deny an SSRF target; its messaging cases require backend approval despite an approval claim in the message body, execute an exact approved send, and deny unlisted destinations and oversized content; its deployment cases execute staging, pause production, then execute the exact approved production invocation.

The Maven `verify` phase runs the CLI after tests. The printed side-effect count comes from the injected mock `ToolExecutor`, while decisions, reasons, approval checks, idempotency, and timeline stages come from the production modules. No output is precomputed and no external system is contacted.

The optional Playground web process uses the JDK HTTP server bound only to loopback. Its decision endpoint accepts only server-defined synthetic case IDs; it never accepts browser-supplied principal, tenant, environment, risk, normalized arguments, or fingerprints as authority. One long-lived runtime owns the real result pipeline, in-memory approval service, audit log, idempotency guard, and per-case mock counters. Approval IDs are generated and bound on the backend, and approval resumes the same stored invocation with a stable idempotency key. Audit and replay endpoints expose only ordered safe event fields and do not receive a pipeline or executor. This process deliberately has no production authentication, persistence, or external connectors and must not be exposed as an approval service.

## Implementation boundary

The v0.4 refund example owns a local H2 runtime, synthetic order ledger, payment
simulator, and three annotated methods inside Playground. It uses JDBC approvals
and audit, with process-local pipeline idempotency and review-display storage.
The preview now includes an independent operation reference. Its immutable
database proposal binds the order and original owner; the approved invocation
binds that reference, amount, expected version, and policy revision.

Before payment, a local transaction conditionally reserves the order and changes
the operation from NOT_STARTED to UNKNOWN. Only its winning caller may dispatch
payment, after that transaction commits. UNKNOWN and terminal records never elect
a replacement caller. All example order writes honor the reservation. The local
policy snapshot is held through the initial attempt, as in v0.3.

Authoritative downstream receipts must match both reference and complete refund
command. A local settlement transaction then updates the balance/version,
releases the reservation, and records SUCCEEDED or FAILED together. Missing,
mismatched, or unavailable evidence keeps the reservation. Reconciliation calls
only the read-only `PaymentQuery` port; it cannot issue payment. Inspection and
reconciliation require the trusted original principal, tenant, and environment.

The original callback result remains an immutable cached snapshot; independent
inspection supplies current business status. Reconciliation does not rewrite past
decision-audit events or cached results. The business outcome record is not an
append-only reconciliation history. Payment state is retained in a simulator
object and H2 retains the business records while services are rebuilt. Actual JVM
crashes, remote payment providers, reservation retention/cleanup, and production
durability are not demonstrated. There is no timeout-based release or automatic
retry. See `refund-example.md` for the reproducible walkthrough and failure cases.

The first vertical slice used Java policies and in-memory stores to prove semantics. P1 adds framework and storage adapters without moving framework, JDBC, or configuration types into core.
