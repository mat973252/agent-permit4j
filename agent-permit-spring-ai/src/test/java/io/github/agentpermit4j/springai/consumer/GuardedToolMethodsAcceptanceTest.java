package io.github.agentpermit4j.springai.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.springai.AgentPermit;
import io.github.agentpermit4j.springai.GuardedToolMethods;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class GuardedToolMethodsAcceptanceTest {

  @Test
  void registersGenericOverrideOnceAndKeepsApprovalAndProxyAdvice() {
    assertTrue(java.util.Arrays.stream(GenericOrders.class.getMethods())
        .anyMatch(method -> method.isBridge() && method.isAnnotationPresent(Tool.class)));
    for (boolean proxied : new boolean[] {false, true}) {
      var target = new GenericOrders();
      var advisedCalls = new AtomicInteger();
      var factory = new ProxyFactory(target);
      factory.setProxyTargetClass(true);
      factory.addAdvice((MethodInterceptor) invocation -> {
        if (invocation.getMethod().getName().equals("lookup")) advisedCalls.incrementAndGet();
        return invocation.proceed();
      });
      var callbacks = GuardedToolMethods.fromAnnotated(
          dependencies(), proxied ? factory.getProxy() : target);
      assertEquals(1, callbacks.size());
      var callback = callbacks.getFirst();
      var input = "{\"orderId\":\"order-1\"}";
      assertTrue(callback.call(input, context(null)).contains("APPROVAL_REQUIRED"));
      assertEquals(0, target.calls.get());
      var first = callback.call(input, context("approval-1"));
      assertTrue(first.contains("EXECUTED"));
      assertTrue(first.contains("order-1"));
      assertEquals(first, callback.call(input, context("approval-1")));
      assertEquals(1, target.calls.get());
      assertEquals(proxied ? 1 : 0, advisedCalls.get());
    }
  }

  @Test
  void discoversClassProxyAnnotationsWithoutBypassingAdviceApprovalOrIdempotency() {
    var target = new OrderMethods();
    var advisedCalls = new AtomicInteger();
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice((MethodInterceptor) invocation -> {
      if (invocation.getMethod().getName().equals("refund")) advisedCalls.incrementAndGet();
      return invocation.proceed();
    });
    var approvals = new InMemoryApprovalService(Clock.systemUTC(), () -> "approval-1",
        new InvocationFingerprinter());
    var normalized = new AtomicReference<ToolInvocation>();
    var callbacks = GuardedToolMethods.fromAnnotated(
        dependencies(approvals, normalized::set), factory.getProxy());
    assertEquals(2, callbacks.size());
    var callback = callbacks.stream()
        .filter(tool -> tool.getToolDefinition().name().equals("orders.refund")).findFirst().orElseThrow();
    var input = "{\"orderId\":\"order-1\",\"amount\":100}";
    assertTrue(callback.call(input, context(null)).contains("APPROVAL_REQUIRED"));
    assertEquals(0, advisedCalls.get());
    assertEquals(0, target.calls.get());
    var request = approvals.request(normalized.get(), Duration.ofMinutes(5));
    assertTrue(approvals.approve(request.id()).permitted());
    var first = callback.call(input, context("approval-1"));
    assertTrue(first.contains("EXECUTED"));
    assertEquals(first, callback.call(input, context("approval-1")));
    assertTrue(callback.call("{\"orderId\":\"order-2\",\"amount\":100}", context("approval-1"))
        .contains("APPROVAL_INVOCATION_MISMATCH"));
    var otherKey = new HashMap<>(context("approval-1").getContext());
    otherKey.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "another-operation");
    assertTrue(callback.call(input, new ToolContext(otherKey)).contains("APPROVAL_ALREADY_CONSUMED"));
    assertEquals(1, advisedCalls.get());
    assertEquals(1, target.calls.get());
    assertEquals("order-1/99/trusted-user", target.observed);
  }

  @Test
  void rejectsFinalToolOnClassProxyBeforeAnyInvocation() {
    var target = new FinalMethod();
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
    var error = assertThrows(IllegalArgumentException.class,
        () -> GuardedToolMethods.fromAnnotated(dependencies(), factory.getProxy()));
    assertEquals("class proxy tool methods must not be final", error.getMessage());
    assertEquals(0, target.calls.get());
    assertEquals(1, GuardedToolMethods.fromAnnotated(dependencies(), target).size());
  }

  @Test
  void registersMultipleMethodsAndExecutesNormalizedArgumentsOnceWithTrustedIdentity() {
    var target = new OrderMethods();
    var callbacks = GuardedToolMethods.fromAnnotated(dependencies(), target);
    assertEquals(2, callbacks.size());
    var callback = callbacks.stream()
        .filter(tool -> tool.getToolDefinition().name().equals("orders.refund")).findFirst().orElseThrow();
    var pending = callback.call("{\"orderId\":\"order-1\",\"amount\":100}", context(null));
    assertTrue(pending.contains("APPROVAL_REQUIRED"));
    assertEquals(0, target.calls.get());
    var input = "{\"orderId\":\"order-1\",\"amount\":100,\"agentPermit.principalId\":\"forged\"}";
    var first = callback.call(input, context("approval-1"));
    assertTrue(first.contains("EXECUTED"));
    assertEquals(first, callback.call(input, context("approval-1")));
    assertEquals(1, target.calls.get());
    assertEquals("order-1/99/trusted-user", target.observed);
    assertThrows(UnsupportedOperationException.class, () -> callbacks.clear());
  }

  @Test
  void rejectsDuplicateToolNamesAndUnprotectedMethodsAtRegistration() {
    assertThrows(IllegalArgumentException.class,
        () -> GuardedToolMethods.fromAnnotated(dependencies(), new OrderMethods(), new OrderMethods()));
    assertThrows(IllegalArgumentException.class,
        () -> GuardedToolMethods.fromAnnotated(dependencies(), new UnprotectedMethod()));
  }

  private static GuardedToolMethods.Dependencies dependencies() {
    return dependencies(
        (requestId, invocation) -> new GateDecision("approval-1".equals(requestId), "APPROVAL_VALID"),
        invocation -> {});
  }

  private static GuardedToolMethods.Dependencies dependencies(
      ApprovalVerifier approvals, Consumer<ToolInvocation> observed) {
    return new GuardedToolMethods.Dependencies(
        invocation -> new GateDecision(true, "VALID"),
        invocation -> {
          var arguments = new HashMap<>(invocation.arguments());
          arguments.computeIfPresent("amount", (key, value) -> "99");
          return new ToolInvocation(invocation.descriptor(),
              new Principal("trusted-user", Map.of()), invocation.action(),
              invocation.resource(), invocation.context(), arguments);
        },
        invocation -> {
          observed.accept(invocation);
          return new GateDecision(true, "ALLOWED");
        },
        invocation -> new RiskAssessment(RiskLevel.LOW, "READ"),
        approvals,
        new InMemoryResultIdempotencyGuard(), event -> {}, supplied -> supplied);
  }

  private static ToolContext context(String approvalId) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "user");
    values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    values.put(SpringAiToolContextKeys.ENVIRONMENT, "test");
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "one-operation");
    if (approvalId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalId);
    }
    return new ToolContext(values);
  }

  public static class OrderMethods {
    final AtomicInteger calls = new AtomicInteger();
    String observed;

    @Tool(name = "orders.refund", description = "Refund an order")
    @AgentPermit(resourceType = "order", resourceArg = "orderId")
    public String refund(String orderId, long amount, ToolContext context) {
      calls.incrementAndGet();
      observed = orderId + "/" + amount + "/"
          + context.getContext().get(SpringAiToolContextKeys.PRINCIPAL_ID);
      return observed;
    }

    @Tool(name = "orders.lookup", description = "Read an order")
    @AgentPermit(resourceType = "order", resourceArg = "orderId", risk = RiskLevel.LOW)
    public String lookup(String orderId) {
      return orderId;
    }
  }

  public static class UnprotectedMethod {
    @Tool(description = "Must not be silently exposed")
    public String unprotected(String orderId) {
      return orderId;
    }
  }

  public static class FinalMethod {
    final AtomicInteger calls = new AtomicInteger();

    @Tool(description = "Final method cannot be advised on a class proxy")
    @AgentPermit(resourceType = "order", resourceArg = "orderId", risk = RiskLevel.LOW)
    public final String lookup(String orderId) {
      calls.incrementAndGet();
      return orderId;
    }
  }

  public abstract static class GenericBase<T> {
    public abstract T lookup(T orderId);
  }

  public static class GenericOrders extends GenericBase<String> {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    @Tool(name = "orders.lookup", description = "Look up an order through a generic override")
    @AgentPermit(resourceType = "order", resourceArg = "orderId")
    public String lookup(String orderId) {
      calls.incrementAndGet();
      return orderId;
    }
  }
}
