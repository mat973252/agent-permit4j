# Three guarded business tools: order refunds

This source-checkout example uses the v0.5.0 SDK coordinates. Maven Central has the
published release; this checkout may also contain unpublished fixes, including the
[CGLIB candidate](adoption/2026-10-proxy-reproduction.md). It uses synthetic identities,
an embedded H2 business ledger, JDBC approval and audit adapters, and an in-process
payment simulator. No LLM, credentials, network, or real payments are required.

## Run and inspect

From the repository root on Windows:

```powershell
.\mvnw.cmd -B -ntp -pl agent-permit-playground -am verify
```

On other platforms use `./mvnw` with the same arguments. The verify phase runs both
the existing Playground and `RefundDemo`. Its refund section reports:

```text
SCENARIO refund-business
  PREVIEW order=order-1 refundedCents=0->2500 version=0 policy=refund-v1
  REVIEW approver=reviewer-a selfApproval=DENIED
  RESULT decision=EXECUTED status=SUCCEEDED payments=1 refundedCents=2500 version=1 retry=same-result
SCENARIO refund-recovery
  REVIEW approver=reviewer-a selfApproval=DENIED
  RESPONSE status=UNKNOWN payments=1 refundedCents=0
  REBUILT status=UNKNOWN reference=owner-scoped
  RECONCILED status=SUCCEEDED payments=1 paymentRequests=1 refundedCents=2500 retry=same-snapshot
```

The amount is in integer minor units: 2500 cents. The order begins with 10000 cents
paid. The example changes the actual JDBC row, not just an invocation counter.
The existing web console remains a separate demonstration of its original cases;
the refund walkthrough runs in the terminal.

## Adopting existing methods

`RefundTools` contains three public Spring AI `@Tool` methods:

- `orders.lookup`: returns the current balance.
- `orders.previewRefund`: reads the balance, builds a before/after view and creates
  a reviewed approval request. It records a proposal but does not pay.
- `orders.refund`: reserves the order and calls the payment simulator only after
  approval, policy evaluation, and idempotency election; returns a business outcome.

In v0.4 the refund arguments are `operationReference`, `amountCents`,
`expectedVersion`, and `policyRevision`, supplied by `RefundPreview.arguments()`.
The backend stores the actual order under that operation reference. The preview
still displays the order and before/after amounts. Changing the reference or
approved values invalidates approval, and a reference alone cannot authorize a
payment. These demo arguments changed from v0.3; reusable callback APIs are unchanged.

Supply one set of application policies and shared stores, then register only the
objects you intend to expose:

```java
var dependencies = new GuardedToolMethods.Dependencies(
    validator, normalizer, authorizer, riskEvaluator,
    approvals, resultIdempotencyGuard, auditSink, trustedContextResolver);
var callbacks = GuardedToolMethods.fromAnnotated(dependencies, orderTools, otherTools);
// Register these callbacks with the application's Spring AI client.
```

The factory inspects public methods on those explicit objects. Every discovered
`@Tool` must also declare `@AgentPermit`; duplicate tool names and an empty
registration fail at construction. It does not scan the classpath, select Spring
beans, or expose the original unguarded callbacks. In the current source candidate,
annotations are discovered on the public methods of the user class behind a
CGLIB proxy, while invocation still goes through the supplied proxy. Annotated
final methods on a class proxy are rejected because they cannot be advised;
plain-object final methods remain supported. Interface-only annotation discovery
is not provided. This proxy fix is not included in the published 0.5.0 artifacts;
see the [candidate reproduction](adoption/2026-10-proxy-reproduction.md).

Compile tool classes with `-parameters` (Maven: `maven.compiler.parameters=true`).
The current mapper accepts only flat scalar arguments; nested objects and arrays
are rejected. The original method runs inside the pipeline's executor with JSON
reconstructed from the normalized invocation. A `ToolContext` method parameter
receives only the normalized principal ID, tenant ID, and environment. Extra
transport fields, raw model JSON, approval IDs, and idempotency keys are not passed
into the business method. Put business operation identifiers in the normalized
contract when the method needs them.

`trustedContextResolver` can reuse the existing Spring Security bridge. Applications
must derive identity and keys from their own trusted state and expose only the
guarded callbacks. This SDK does not sandbox arbitrary application code.

## Identified approval

Construct either approval service with an application-provided `ApprovalAuthorizer`:

```java
var approvals = new JdbcApprovalService(dataSource, clock, idGenerator,
    new InvocationFingerprinter(), approvalAuthorizer);
var request = approvals.requestReview(normalizedInvocation, Duration.ofMinutes(5));
var decision = approvals.approve(request.id(), normalizedInvocation, authenticatedReviewer);
var receipt = approvals.decision(request.id());
```

Apply both `approval-schema.sql` and the additive `approval-review-schema.sql`
from `io/github/agentpermit4j/jdbc/` using the application's migration
tool. Existing legacy requests continue using the original table and APIs.
The new review table contains only request ID, approver ID, tenant ID, and decision
time. JDBC writes that record and the approval state in one transaction. A failed
record write rolls the approval back; concurrent reviewers retain the first
successful decision. `decision` is a trusted application read API, not an
authenticated HTTP endpoint.

The example authorizer rejects self approval, another tenant, and a principal
without the reviewer role. These are explicit application policies, not hardcoded
role assumptions in the library. Null/throwing authorizers fail closed. The old
constructor has no reviewer policy and denies identified approvals until one is
configured. `approve(requestId)` cannot approve a reviewed request.

The library stores the invocation fingerprint rather than raw arguments. The
application retains the exact normalized proposal to display and submit for
review. `RefundReviews` looks up its backend-owned preview by ID; a caller cannot
approve an edited client-provided preview. Its proposal map is process-local, so
this sample does not demonstrate restarting and approving pending review displays.
The business operation record itself is now persisted separately and remains
inspectable when application services are rebuilt.

## Resource and failure semantics

The approved invocation binds requester, tenant, environment, operation reference,
amount, expected order version, and policy revision. The immutable stored proposal
binds its order and owner. Policy is checked during preflight and held stable
through the local example's initial attempt.

Before contacting payment, one database transaction checks tenant, order, version,
remaining balance, and absence of another reservation. It reserves the order and
moves the operation from NOT_STARTED to UNKNOWN. Only the caller that commits
this transition may call payment. A stale/competing proposal becomes a known
FAILED result with `REFUND_PRECONDITION_CHANGED` and no payment.

A confirmed downstream receipt must match the reference and full stored command.
Success updates the refund balance/version, releases the reservation, and records
SUCCEEDED in one local transaction. Confirmed rejection leaves the balance
unchanged, releases the reservation, and records FAILED. A timeout, lost response,
or failed settlement commit leaves an uncertain operation reserved for inspection.
An empty downstream lookup is not proof of rejection and cannot release it.

```text
NOT_STARTED -- commit reservation --> UNKNOWN -- verified success --> SUCCEEDED
     |                                  |
     +-- invalid order condition --> FAILED <-- verified rejection --+
```

`ExecutionOutcome` contains only `reference`, `status`, and `reasonCode`.
`DecisionOutcome.EXECUTED` still means the guarded Java method returned normally;
the nested business outcome may be UNKNOWN or FAILED. Applications must inspect
that status before treating the refund as successful. An unexpected method
exception keeps the existing pipeline `FAILED / EXECUTION_FAILED` behavior.

## Inspect and reconcile

The preview supplies an independent opaque reference before any payment attempt,
so it is available even if the entire execution response is lost. It is distinct
from the approval ID and the pipeline idempotency key. The simulator uses it to
identify its downstream operation, but possession grants no read or write authority.

```java
var reference = preview.operationReference();
var observed = workspace.inspect(reference, authenticatedOwner, trustedContext);
var resolved = workspace.reconcile(reference, authenticatedOwner, trustedContext);
```

Both calls require the original trusted principal, tenant, and environment; unknown
or unauthorized references return an empty result without querying payment.
These are trusted application APIs, not authenticated HTTP endpoints. Never copy
untrusted request fields into `Principal` or `InvocationContext`.

Inspection reads the local business state. Reconciliation calls only `PaymentQuery`
and conditionally records conclusive evidence; it never calls the payment method.
Unavailable, mismatched, or inconclusive evidence keeps UNKNOWN and its reservation.
Repeated/concurrent reconciliation cannot apply the balance adjustment twice.

Same-key callback retries return their original cached snapshot, subject to
current preflight checks. A later reconciliation does not mutate that snapshot or
past decision-audit events; call `inspect` for current status. Expired approval or
changed policy can still prevent access to the cached callback response.

`workspace.restartServices()` constructs fresh services, callbacks, approval/audit
clients, and a payment client over the retained H2 database and simulator state.
Already claimed operations cannot elect another payment caller after this rebuild,
even though the pipeline's in-memory cache has been replaced. The current local
policy revision is preserved. Pending review-display maps are not recovered.

This demonstrates application-service recovery, not a killed JVM or remote provider.
H2 and simulator state live in this process; the example provisions a fresh local
schema and does not ship a migration from the v0.3 demo. Production integrations
need durable authoritative operation records, authenticated downstream queries,
and all business writers to honor reservations. A lost reservation acknowledgment
before payment can remain UNKNOWN indefinitely without conclusive evidence.
There is no automatic retry, timeout-based release, cleanup, or append-only
reconciliation history. Distributed exactly-once is not a guarantee of this demo.

## Executable evidence

- `RefundLedgerAcceptanceTest`: real row changes, same-key concurrent execution,
  version/tenant/balance conditions, known payment failure rollback, and an order
  change injected during approval verification before the executor runs.
- `GuardedToolMethodsAcceptanceTest`: actual method dispatch, normalization,
  trusted identity, retries, and duplicate/unprotected registration rejection.
- `ReviewedApprovalAcceptanceTest`: in-memory and JDBC identified review,
  original decision retention, and parameter/policy fingerprint rejection.
- `JdbcApprovalReviewTest`: concurrent reviewers and atomic decision-write failure.
- `RefundWorkspaceAcceptanceTest`: the complete three-tool flow, tampered amount,
  policy replacement, an order changed after approval, invalid missing numbers,
  eight concurrent callback retries, and consumed-approval rejection with a new key.
- `RefundLostResponseTest`: a successful payment survives a lost response and local rollback.
- `RefundReconciliationAcceptanceTest`: conclusive/uncertain outcomes, original-owner
  isolation, service rebuilding, competing proposals, and concurrent service dispatch/reconciliation.
- `RefundReconciliationFaultTest`: failures before/after settlement commit,
  lost reservation acknowledgment, and mismatched downstream evidence.
- `RefundWorkspaceRecoveryTest`: approved callbacks expose the safe reference,
  rebuilt services reconcile without another payment, and the old cached snapshot stays unchanged.

The next adoption check is to have independent users integrate three existing
tools and measure time and extra wiring. That usability target has not yet been
measured by this implementation.
