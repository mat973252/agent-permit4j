package io.github.agentpermit4j.springai;

import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.audit.AuditSink;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InvocationNormalizer;
import io.github.agentpermit4j.execution.InvocationValidator;
import io.github.agentpermit4j.execution.ResultDecisionPipeline;
import io.github.agentpermit4j.execution.ResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultToolExecutor;
import io.github.agentpermit4j.policy.Authorizer;
import io.github.agentpermit4j.policy.RiskEvaluator;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.util.JsonHelper;
import org.springframework.util.ClassUtils;

/** Explicitly registers annotated methods on application-supplied objects, without bean scanning. */
public final class GuardedToolMethods {

  private GuardedToolMethods() {}

  public static List<GuardedToolCallback> fromAnnotated(Dependencies dependencies, Object... targets) {
    Objects.requireNonNull(dependencies, "dependencies");
    var callbacks = new TreeMap<String, GuardedToolCallback>();
    for (var target : Objects.requireNonNull(targets, "targets")) {
      var methods = ClassUtils.getUserClass(Objects.requireNonNull(target, "target")).getMethods();
      Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
      for (var method : methods) {
        if (method.isAnnotationPresent(Tool.class)) {
          var callback = create(dependencies, target, method);
          if (callbacks.putIfAbsent(callback.getToolDefinition().name(), callback) != null) {
            throw new IllegalArgumentException("duplicate tool name");
          }
        }
      }
    }
    if (callbacks.isEmpty()) {
      throw new IllegalArgumentException("at least one public annotated tool is required");
    }
    return List.copyOf(callbacks.values());
  }

  private static GuardedToolCallback create(Dependencies dependencies, Object target, Method method) {
    if (ClassUtils.getUserClass(target) != target.getClass() && Modifier.isFinal(method.getModifiers())) {
      throw new IllegalArgumentException("class proxy tool methods must not be final");
    }
    requireParameterNames(method);
    var definition = ToolDefinitions.from(method);
    var policy = AgentPermitMethodPolicy.from(definition, method);
    var delegate = MethodToolCallback.builder()
        .toolDefinition(definition).toolMethod(method).toolObject(target).build();
    var json = new JsonHelper();
    ResultToolExecutor executor = invocation -> delegate.call(
        json.toJson(invocation.arguments()), methodContext(invocation));
    var pipeline = new ResultDecisionPipeline(policy.decorate(dependencies.bind(executor)));
    return new GuardedToolCallback(definition, pipeline, policy.contract(),
        dependencies.contextResolver(), policy::errorMessage);
  }

  private static ToolContext methodContext(ToolInvocation invocation) {
    return new ToolContext(Map.of(
        SpringAiToolContextKeys.PRINCIPAL_ID, invocation.principal().id(),
        SpringAiToolContextKeys.TENANT_ID, invocation.context().tenantId(),
        SpringAiToolContextKeys.ENVIRONMENT, invocation.context().environment()));
  }

  private static void requireParameterNames(Method method) {
    for (var parameter : method.getParameters()) {
      if (parameter.getType() != ToolContext.class && !parameter.isNamePresent()) {
        throw new IllegalArgumentException("tool methods must be compiled with -parameters");
      }
    }
  }

  public record Dependencies(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      Authorizer authorizer,
      RiskEvaluator riskEvaluator,
      ApprovalVerifier approvalVerifier,
      ResultIdempotencyGuard idempotencyGuard,
      AuditSink auditSink,
      TrustedToolContextResolver contextResolver) {

    public Dependencies {
      Objects.requireNonNull(validator, "validator");
      Objects.requireNonNull(normalizer, "normalizer");
      Objects.requireNonNull(authorizer, "authorizer");
      Objects.requireNonNull(riskEvaluator, "riskEvaluator");
      Objects.requireNonNull(approvalVerifier, "approvalVerifier");
      Objects.requireNonNull(idempotencyGuard, "idempotencyGuard");
      Objects.requireNonNull(auditSink, "auditSink");
      Objects.requireNonNull(contextResolver, "contextResolver");
    }

    private ResultDecisionPipeline.Dependencies bind(ResultToolExecutor executor) {
      return new ResultDecisionPipeline.Dependencies(validator, normalizer, authorizer,
          riskEvaluator, approvalVerifier, idempotencyGuard, executor, auditSink);
    }
  }
}
