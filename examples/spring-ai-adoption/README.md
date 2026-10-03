# Adopt three existing Spring AI methods

This standalone Java 21 application consumes AgentPermit4j as Maven artifacts.
It has no SDK parent POM, no reactor membership, and no Playground dependency.
The three methods query inventory, prepare a review, and reserve the reviewed quantity.
All business state, identity, approvals, and inventory changes are local synthetic data.
There is no LLM, web server, external database, or payment account.

## Run with the published SDK

The example depends on AgentPermit4j `0.5.0`, available from Maven Central.
From this checkout, run the standalone consumer without installing the SDK:

```powershell
$publicCache = Join-Path ([IO.Path]::GetTempPath()) ('agentpermit-public-' + [guid]::NewGuid().ToString('N'))
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=$publicCache" -f examples/spring-ai-adoption/pom.xml verify
```

On Linux/macOS with a POSIX shell:

```sh
public_cache=$(mktemp -d)
./mvnw -B -ntp "-Dmaven.repo.local=$public_cache" -f examples/spring-ai-adoption/pom.xml verify
```

When copying the example outside this repository, an installed Maven can run
`mvn -B -ntp "-Dmaven.repo.local=$public_cache" verify` with that separate cache.
Never install candidate SDK artifacts into this public-consumption cache.
To test local SDK changes, use the isolated candidate script below. The candidate
currently keeps the `0.5.0` coordinates, so a default-cache install could silently
replace the published SDK in later consumer runs.

The tests and terminal demonstration print:

```text
ADOPTION tools=3 lookup=EXECUTED pending=APPROVAL_REQUIRED reviewed=true result=EXECUTED writes=1 available=8 retrySame=true
```

The inventory starts at 10. A reviewer approves a reservation of 2. Concurrent
and later same-key retries return the same result, leaving 8 units and one write.

## Where to connect your application

| Responsibility | Example | Your application supplies |
| --- | --- | --- |
| Existing business methods | [InventoryTools](src/main/java/example/inventory/InventoryTools.java) | Public `@Tool` methods with `@AgentPermit` and named scalar parameters |
| Explicit SDK wiring | [InventoryApplication](src/main/java/example/inventory/InventoryApplication.java) | Validator, normalizer, authorization/risk policy, stores, audit, trusted context |
| Concrete review | [InventoryReviews](src/main/java/example/inventory/InventoryReviews.java), [InventoryPreview](src/main/java/example/inventory/InventoryPreview.java) | Backend-owned normalized proposal and authenticated reviewer policy |
| Business invariant | [InventoryStore](src/main/java/example/inventory/InventoryStore.java) | Atomic version/quantity checks in your actual write boundary |
| Host-controlled invocation | [InventoryDemo](src/main/java/example/inventory/InventoryDemo.java) | Stable operation key and trusted identity/tenant/environment |

Register the list returned by `app.tools()` as Spring AI callbacks. Register only
those guarded callbacks for these business methods; exposing the original tool
object separately creates an unprotected path.

`InventoryApplication` constructs one shared approval service, result-idempotency
guard, and audit log. These instances live as long as the application. Rebuilding
them per call loses approval state and retry coordination.

The example's `context(...)` helper represents an authenticated transport boundary.
It is called by the local host code, not by the model. In a web application derive
identity and tenant from the authenticated session, and derive the operation key
from a stable application operation. Do not pass browser/model identity claims
directly to this helper. Use the [trusted-context integration](../../docs/integration-reference.md)
for Spring Security wiring and thread propagation limits.

The reviewer is an authenticated principal supplied by the application. The local
policy checks reviewer role, matching tenant, and separation from the requester.
The review API receives an ID and looks up the stored proposal; it does not approve
a client-supplied replacement invocation.

`InventoryPreview.invocation()` describes exactly the annotated reservation:
tool metadata, requester, resource, context, quantity, and expected version.
If you change the method's annotation contract, update this backend proposal too.
The approved-write test catches a mismatch. The example uses an identity normalizer;
if your application normalizes inputs, prepare the review from the same normalized values.

## What the example proves

The [consumer acceptance tests](src/test/java/example/inventory/InventoryAdoptionTest.java)
exercise all three methods, no-approval/no-write, eight overlapping same-key calls,
quantity tampering, consumed-approval reuse with another key, cross-tenant access,
model identity spoofing, successful-cache retries with changed quantity or trusted
tenant/principal, missing context/nested input, reviewer authorization,
stale resource versions, and read-only audit replay without input/output secrets.

The concurrency test pauses the owner inside the real idempotency guard before
its business callback runs, waits for seven retries to enter that guard boundary,
then releases it. The test checks identical results, one business write and eight
remaining units. The observer delegates coordination to the published SDK; it
does not implement its own cache or claim cross-process coordination.

Compile with `maven.compiler.parameters=true`. This example intentionally uses
flat scalar arguments. Nested DTOs, arrays, and interface-only annotation discovery
are not silently adapted. Public `0.5.0` also lacks the later CGLIB discovery fix.
The [unpublished source candidate](../../docs/adoption/2026-10-proxy-reproduction.md)
supports CGLIB class proxies while preserving advice; it rejects final tool methods
on those proxies, but permits them on plain objects. Record unsupported original signatures in the
[independent adoption worksheet](../../docs/adoption/2026-09-first-integration.md).

The local inventory lock proves this application's atomic write condition. It does
not provide cross-process persistence. The in-memory stores lose state on restart.
For Redis/JDBC contracts and the separate UNKNOWN outcome recovery example, see
the [integration reference](../../docs/integration-reference.md) and
[refund walkthrough](../../docs/refund-example.md).

## Verify source candidate consumption in isolation

From the SDK checkout, run the maintained PowerShell script (PowerShell 7 via
`pwsh` on Linux/macOS, or PowerShell on Windows):

```powershell
.\scripts\verify-adoption.ps1
```

The script creates a fresh temporary Maven repository by default, clean-builds the
SDK with sources/Javadoc, copies only this example's POM and source into a separate
temporary directory, then runs the consumer online and offline. It checks every
library's main, source and documentation artifact and retains unsigned files under
`target/candidate/<version>/`. Temporary paths are printed for inspection.

For a repeat build, `-MavenRepository <absolute-path>` reuses downloaded dependencies
while reinstalling the current SDK. Record whether a run used a fresh or reused
repository. Neither mode measures an independent developer's integration time.
