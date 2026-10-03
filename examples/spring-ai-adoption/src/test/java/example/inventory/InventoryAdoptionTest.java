package example.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

class InventoryAdoptionTest {
  private static final JsonHelper JSON = new JsonHelper();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);
  private static final Principal REVIEWER = new Principal("reviewer-a", Map.of("role", "reviewer", "tenant", "tenant-a"));
  private InventoryApplication app = new InventoryApplication(CLOCK);

  @Test
  void threeMethodsReadPreviewAndExecuteOnlyTheReviewedChange() {
    assertEquals(3, app.tools().size());
    assertEquals("EXECUTED", envelope(call("inventory.lookup", "{\"sku\":\"internal-sku-7\"}", "lookup", null)).get("outcome"));
    var preview = preview();
    assertEquals(10, preview.availableBefore());
    assertEquals(8, preview.availableAfter());
    assertEquals(0, app.inventory().writes());
    assertEquals("APPROVAL_REQUIRED", envelope(reserve(preview, "reserve", null)).get("outcome"));
    assertEquals(0, app.inventory().writes());
    assertTrue(app.approve(preview.reviewId(), REVIEWER).permitted());
    assertEquals("EXECUTED", envelope(reserve(preview, "reserve", preview.reviewId())).get("outcome"));
    assertEquals(8, app.inventory().available());
    assertEquals(1, app.inventory().writes());
  }

  @Test
  void concurrentRetriesReturnTheSameOutputAndReserveStockOnce() throws Exception {
    var guard = new BlockingIdempotencyGuard();
    app = new InventoryApplication(CLOCK, guard);
    var preview = approvedPreview();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<String>>();
      try {
        futures.add(pool.submit(() -> reserve(preview, "concurrent", preview.reviewId())));
        assertTrue(guard.ownerEntered.await(10, TimeUnit.SECONDS));
        for (int index = 0; index < 7; index++) {
          futures.add(pool.submit(() -> reserve(preview, "concurrent", preview.reviewId())));
        }
        assertTrue(guard.arrivals.await(10, TimeUnit.SECONDS), "all retries must enter the guard while its owner is blocked");
        assertEquals(1, guard.executions.get());
        for (var future : futures) assertFalse(future.isDone());
      } finally {
        guard.release.countDown();
      }
      var first = futures.getFirst().get(10, TimeUnit.SECONDS);
      assertEquals("EXECUTED", envelope(first).get("outcome"));
      for (var future : futures) {
        assertEquals(first, future.get(10, TimeUnit.SECONDS));
      }
      assertEquals(first, reserve(preview, "concurrent", preview.reviewId()));
      assertEquals(1, guard.executions.get());
    }
    assertEquals(1, app.inventory().writes());
    assertEquals(8, app.inventory().available());
  }

  @Test
  void changedQuantityInvalidatesTheExactApprovalBeforeAnyWrite() {
    var preview = approvedPreview();
    var changed = new HashMap<>(preview.arguments());
    changed.put("quantity", "3");
    var result = call("inventory.reserve", JSON.toJson(changed), "changed", preview.reviewId());
    assertEquals("APPROVAL_INVOCATION_MISMATCH", envelope(result).get("reasonCode"));
    assertEquals(0, app.inventory().writes());
  }

  @Test
  void successfulRetryCacheDoesNotBypassChangedQuantityOrTrustedIdentity() {
    var preview = approvedPreview();
    assertEquals("EXECUTED", envelope(reserve(preview, "cached", preview.reviewId())).get("outcome"));
    var changed = new HashMap<>(preview.arguments());
    changed.put("quantity", "3");
    var changedResponse = envelope(call("inventory.reserve", JSON.toJson(changed), "cached", preview.reviewId()));
    assertEquals("APPROVAL_REQUIRED", changedResponse.get("outcome"));
    assertEquals("APPROVAL_INVOCATION_MISMATCH", changedResponse.get("reasonCode"));
    var callback = app.tools().stream().filter(t -> t.getToolDefinition().name().equals("inventory.reserve")).findFirst().orElseThrow();
    for (var identity : new String[][] {{"operator-a", "tenant-b"}, {"attacker", "tenant-a"}}) {
      var trusted = InventoryApplication.context(identity[0], identity[1], "cached", preview.reviewId());
      var response = envelope(callback.call(JSON.toJson(preview.arguments()), trusted));
      assertEquals("DENIED", response.get("outcome"));
      assertEquals("INVENTORY_ACCESS_DENIED", response.get("reasonCode"));
    }
    assertEquals(1, app.inventory().writes());
    assertEquals(8, app.inventory().available());
    assertEquals("EXECUTED", envelope(reserve(preview, "cached", preview.reviewId())).get("outcome"));
  }

  @Test
  void consumedApprovalCannotAuthorizeAnotherIdempotencyKey() {
    var preview = approvedPreview();
    reserve(preview, "first", preview.reviewId());
    assertEquals("APPROVAL_ALREADY_CONSUMED", envelope(reserve(preview, "second", preview.reviewId())).get("reasonCode"));
    assertEquals(1, app.inventory().writes());
  }

  @Test
  void anotherTenantCannotReadPreviewOrExecuteEvenWithAnApproval() {
    var preview = approvedPreview();
    var context = context("other-tenant", preview.reviewId());
    var changed = new HashMap<String, Object>(context.getContext());
    changed.put(SpringAiToolContextKeys.TENANT_ID, "tenant-b");
    for (var tool : app.tools()) {
      var input = switch (tool.getToolDefinition().name()) {
        case "inventory.reserve" -> JSON.toJson(preview.arguments());
        case "inventory.preview" -> "{\"sku\":\"internal-sku-7\",\"quantity\":2}";
        default -> "{\"sku\":\"internal-sku-7\"}";
      };
      assertEquals("INVENTORY_ACCESS_DENIED", envelope(tool.call(input, new ToolContext(changed))).get("reasonCode"));
    }
    assertEquals(0, app.inventory().writes());
  }

  @Test
  void modelIdentityFieldsCannotReplaceTrustedTransportIdentity() {
    var preview = approvedPreview();
    var input = new HashMap<>(preview.arguments());
    input.put(SpringAiToolContextKeys.PRINCIPAL_ID, "attacker");
    input.put(SpringAiToolContextKeys.TENANT_ID, "tenant-b");
    assertEquals("EXECUTED", envelope(call("inventory.reserve", JSON.toJson(input), "forged", preview.reviewId())).get("outcome"));
    assertEquals("operator-a", app.inventory().lastOperator());
  }

  @Test
  void missingIdentityAndNestedInputFailBeforeInvokingBusinessCode() {
    var callback = app.tools().stream().filter(t -> t.getToolDefinition().name().equals("inventory.reserve")).findFirst().orElseThrow();
    assertEquals("SPRING_AI_CONTEXT_INVALID", envelope(callback.call("{\"sku\":\"internal-sku-7\"}", new ToolContext(Map.of()))).get("reasonCode"));
    assertEquals("SPRING_AI_INPUT_INVALID", envelope(call("inventory.reserve", "{\"sku\":{\"id\":\"internal-sku-7\"}}", "nested", null)).get("reasonCode"));
    assertEquals(0, app.inventory().writes());
  }

  @Test
  void reviewRequiresAnotherAuthorizedReviewerInTheSameTenant() {
    var preview = preview();
    assertFalse(app.approve(preview.reviewId(), new Principal("operator-a", Map.of("role", "reviewer", "tenant", "tenant-a"))).permitted());
    assertFalse(app.approve(preview.reviewId(), new Principal("reviewer-b", Map.of("role", "reviewer", "tenant", "tenant-b"))).permitted());
    assertFalse(app.approve(preview.reviewId(), new Principal("viewer", Map.of("tenant", "tenant-a"))).permitted());
    assertTrue(app.approve(preview.reviewId(), REVIEWER).permitted());
    assertEquals(0, app.inventory().writes());
  }

  @Test
  void stalePreviewCannotOverwriteACompetingInventoryChange() {
    var preview = approvedPreview();
    app.inventory().reserve(1, 0, "local-admin");
    assertEquals("FAILED", envelope(reserve(preview, "stale", preview.reviewId())).get("outcome"));
    assertEquals(9, app.inventory().available());
    assertEquals(1, app.inventory().writes());
  }

  @Test
  void auditReplayIsReadOnlyAndExcludesInputOutputAndTransportSecrets() {
    var preview = approvedPreview();
    var result = reserve(preview, "private-retry-key", preview.reviewId());
    var events = app.audit().snapshot();
    assertFalse(events.isEmpty());
    var serialized = JSON.toJson(events);
    for (var forbidden : new String[] {"internal-sku-7", "quantity", preview.reviewId(), "private-retry-key", (String) envelope(result).get("output")}) {
      assertFalse(serialized.contains(forbidden));
    }
    for (var event : events) {
      app.audit().replaySafeView(event.timelineId());
    }
    assertEquals(1, app.inventory().writes());
  }

  private InventoryPreview preview() {
    var response = call("inventory.preview", "{\"sku\":\"internal-sku-7\",\"quantity\":2}", "preview", null);
    assertEquals("EXECUTED", envelope(response).get("outcome"));
    return JSON.fromJson((String) envelope(response).get("output"), InventoryPreview.class);
  }

  private InventoryPreview approvedPreview() {
    var preview = preview();
    assertTrue(app.approve(preview.reviewId(), REVIEWER).permitted());
    return preview;
  }

  private String reserve(InventoryPreview preview, String key, String approval) {
    return call("inventory.reserve", JSON.toJson(preview.arguments()), key, approval);
  }

  private String call(String tool, String input, String key, String approval) {
    return app.tools().stream().filter(t -> t.getToolDefinition().name().equals(tool))
        .findFirst().orElseThrow().call(input, context(key, approval));
  }

  private static ToolContext context(String key, String approval) {
    return InventoryApplication.context("operator-a", "tenant-a", key, approval);
  }

  private static Map<String, Object> envelope(String result) {
    return JSON.fromJsonToMap(result);
  }
}
