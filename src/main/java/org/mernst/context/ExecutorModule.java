package org.mernst.context;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import io.grpc.Context;
import org.mernst.concurrent.Executor;

import javax.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ExecutorModule extends AbstractModule {
  @Override
  protected void configure() {
    bindScope(ContextScoped.class, new ScopeContext());
    bind(java.util.concurrent.Executor.class).to(Executor.class);
  }

  @Provides
  @Singleton
  Executor contextPropagating() {
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());

    pool.setRemoveOnCancelPolicy(true);
    ListeningScheduledExecutorService executorService = MoreExecutors.listeningDecorator(pool);
    return new PropagatingExecutor(executorService, Context::current);
  }

  private static class PropagatingExecutor implements Executor {
    private final ListeningScheduledExecutorService executorService;
    private final Supplier<Context> context;

    public PropagatingExecutor(
        ListeningScheduledExecutorService executorService, Supplier<Context> context) {
      this.executorService = executorService;
      this.context = context;
    }

    @Override
    public Cancellable scheduleAfter(Duration delay, Runnable r) {
      ScheduledFuture<?> f =
          executorService.schedule(wrap(r), delay.toNanos(), TimeUnit.NANOSECONDS);
      return () -> f.cancel(false);
    }

    @Override
    public void execute(Runnable runnable) {
      executorService.execute(wrap(runnable));
    }

    private Runnable wrap(Runnable r) {
      return context.get().wrap(r);
    }

    @Override
    public Executor captureContext() {
      Context context = this.context.get();
      return new PropagatingExecutor(executorService, () -> context);
    }
  }
}
