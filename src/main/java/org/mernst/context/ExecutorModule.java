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
    return new PropagatingExecutor(MoreExecutors.listeningDecorator(pool));
  }

  private static class PropagatingExecutor implements Executor {
    private final ListeningScheduledExecutorService executorService;

    public PropagatingExecutor(
        ListeningScheduledExecutorService executorService) {
      this.executorService = executorService;
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
      return Context.current().wrap(r);
    }
  }
}
