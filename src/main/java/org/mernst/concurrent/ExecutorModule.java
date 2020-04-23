package org.mernst.concurrent;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import javax.inject.Singleton;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ExecutorModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(Executor.class).to(ScheduledExecutorService.class);
  }

  @Provides
  @Singleton
  static ScheduledExecutorService executor() {
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(java.lang.Runtime.getRuntime().availableProcessors());
    pool.setRemoveOnCancelPolicy(true); // esp. deadlines
    return pool;
  }
}
