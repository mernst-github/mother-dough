package org.mernst.context;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import javax.inject.Singleton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ExecutorsModule extends AbstractModule {
  @Override
  protected void configure() {
    bindScope(ContextScoped.class, new ScopeContext());
    bind(ListeningExecutorService.class).to(ListeningScheduledExecutorService.class);
    bind(ScheduledExecutorService.class).to(ListeningScheduledExecutorService.class);
    bind(ExecutorService.class).to(ListeningScheduledExecutorService.class);
    bind(java.util.concurrent.Executor.class).to(ListeningScheduledExecutorService.class);
  }

  @Provides
  @Singleton
  ListeningScheduledExecutorService executorService() {
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());

    pool.setRemoveOnCancelPolicy(true);
    return MoreExecutors.listeningDecorator(new ContextPropagatingScheduledExecutorService(pool));
  }
}
