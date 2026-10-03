package example.inventory;

import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ToolExecutionResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Observes the public guard boundary; the real SDK still owns coordination and caching. */
final class BlockingIdempotencyGuard implements ResultIdempotencyGuard {
  final CountDownLatch ownerEntered = new CountDownLatch(1);
  final CountDownLatch arrivals = new CountDownLatch(8);
  final CountDownLatch release = new CountDownLatch(1);
  final AtomicInteger executions = new AtomicInteger();
  private final ResultIdempotencyGuard delegate = new InMemoryResultIdempotencyGuard();

  @Override
  public ToolExecutionResult executeOnce(String key, ToolInvocation invocation, Supplier<ToolExecutionResult> sideEffect) {
    return executeOnce(key, invocation, null, sideEffect);
  }

  @Override
  public ToolExecutionResult executeOnce(String key, ToolInvocation invocation, String approval, Supplier<ToolExecutionResult> sideEffect) {
    if (!key.equals("concurrent")) return delegate.executeOnce(key, invocation, approval, sideEffect);
    arrivals.countDown();
    return delegate.executeOnce(key, invocation, approval, () -> {
      executions.incrementAndGet();
      ownerEntered.countDown();
      try {
        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("owner was not released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("owner interrupted", interrupted);
      }
      return sideEffect.get();
    });
  }
}
