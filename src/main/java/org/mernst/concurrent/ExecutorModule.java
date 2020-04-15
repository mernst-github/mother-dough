package org.mernst.concurrent;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import javax.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ExecutorModule extends AbstractModule {

  /** Adapts a regular ScheduledExecutorService to our interface. */
  @Provides
  @Singleton
  Executor executor(ScheduledExecutorService executorService) {
    return new Executor() {
      @Override
      public Cancellable scheduleAfter(Duration delay, Runnable r) {
        ScheduledFuture<?> f = executorService.schedule(r, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> f.cancel(false);
      }

      @Override
      public void execute(Runnable runnable) {
        executorService.execute(runnable);
      }
    };
  }
}
