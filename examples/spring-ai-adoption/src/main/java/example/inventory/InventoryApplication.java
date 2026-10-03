package example.inventory;

import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.audit.InMemoryAuditLog;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultIdempotencyGuard;
import io.github.agentpermit4j.springai.GuardedToolCallback;
import io.github.agentpermit4j.springai.GuardedToolMethods;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;

/** Application-owned wiring for one synthetic tenant; this is not an authentication server. */
public final class InventoryApplication {
  private final InventoryStore inventory = new InventoryStore();
  private final InMemoryAuditLog audit = new InMemoryAuditLog();
  private final InventoryReviews reviews;
  private final List<GuardedToolCallback> tools;

  public InventoryApplication(Clock clock) {
    this(clock, new InMemoryResultIdempotencyGuard());
  }

  InventoryApplication(Clock clock, ResultIdempotencyGuard idempotency) {
    var approvals = new InMemoryApprovalService(clock, () -> UUID.randomUUID().toString(),
        new InvocationFingerprinter(), InventoryApplication::authorizeReviewer);
    reviews = new InventoryReviews(inventory, approvals);
    var dependencies = new GuardedToolMethods.Dependencies(
        InventoryApplication::validate, invocation -> invocation,
        InventoryApplication::authorize, InventoryApplication::assessRisk,
        approvals, idempotency, audit, supplied -> supplied);
    tools = GuardedToolMethods.fromAnnotated(dependencies, new InventoryTools(inventory, reviews));
  }

  public List<GuardedToolCallback> tools() { return tools; }
  public InventoryStore inventory() { return inventory; }
  public InMemoryAuditLog audit() { return audit; }

  /** Trusted backend API: authenticate the reviewer before constructing their principal or attributes. */
  public GateDecision approve(String reviewId, Principal authenticatedReviewer) {
    return reviews.approve(reviewId, authenticatedReviewer);
  }

  /** Call only after authenticating the session and deriving a stable operation key. */
  public static ToolContext context(String principal, String tenant, String key, String approval) {
    var trusted = new HashMap<String, Object>();
    trusted.put(SpringAiToolContextKeys.PRINCIPAL_ID, principal);
    trusted.put(SpringAiToolContextKeys.TENANT_ID, tenant);
    trusted.put(SpringAiToolContextKeys.ENVIRONMENT, "demo");
    trusted.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, key);
    if (approval != null) {
      trusted.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approval);
    }
    return new ToolContext(trusted);
  }

  private static GateDecision validate(ToolInvocation invocation) {
    var arguments = invocation.arguments();
    var name = invocation.descriptor().name();
    var expected = switch (name) {
      case "inventory.lookup" -> Set.of("sku");
      case "inventory.preview" -> Set.of("sku", "quantity");
      case "inventory.reserve" -> Set.of("sku", "quantity", "expectedVersion");
      default -> Set.<String>of();
    };
    try {
      var valid = arguments.keySet().equals(expected) && !expected.isEmpty()
          && (!arguments.containsKey("quantity") || Integer.parseInt(arguments.get("quantity")) > 0)
          && (!arguments.containsKey("expectedVersion") || Integer.parseInt(arguments.get("expectedVersion")) >= 0);
      return new GateDecision(valid, valid ? "INVENTORY_INPUT_VALID" : "INVENTORY_INPUT_INVALID");
    } catch (NumberFormatException invalid) {
      return new GateDecision(false, "INVENTORY_INPUT_INVALID");
    }
  }

  private static GateDecision authorize(ToolInvocation invocation) {
    var allowed = invocation.principal().id().equals("operator-a")
        && invocation.context().tenantId().equals("tenant-a")
        && invocation.context().environment().equals("demo")
        && invocation.resource().type().equals("inventory")
        && invocation.resource().identifier().equals("internal-sku-7");
    return new GateDecision(allowed, allowed ? "INVENTORY_ALLOWED" : "INVENTORY_ACCESS_DENIED");
  }

  private static RiskAssessment assessRisk(ToolInvocation invocation) {
    return switch (invocation.descriptor().name()) {
      case "inventory.lookup", "inventory.preview" -> new RiskAssessment(RiskLevel.LOW, "INVENTORY_NO_RESERVATION");
      case "inventory.reserve" -> new RiskAssessment(RiskLevel.HIGH, "INVENTORY_RESERVATION");
      default -> new RiskAssessment(RiskLevel.DENY, "INVENTORY_TOOL_UNKNOWN");
    };
  }

  private static GateDecision authorizeReviewer(Principal reviewer, ToolInvocation invocation) {
    var allowed = reviewer != null && "reviewer".equals(reviewer.attributes().get("role"))
        && invocation.context().tenantId().equals(reviewer.attributes().get("tenant"))
        && !invocation.principal().id().equals(reviewer.id());
    return new GateDecision(allowed, allowed ? "INVENTORY_REVIEW_ALLOWED" : "INVENTORY_REVIEW_DENIED");
  }
}
