package org.mernst.concurrent;

import java.time.Duration;

/**
 * An executor with scheduling. Much smaller interface than ScheduledExecutorService and thus easier
 * to fake.
 */
public interface Executor extends java.util.concurrent.Executor {
  /** Only what we need from the result future. */
  interface Cancellable {
    void cancel();
  }

  Cancellable scheduleAfter(Duration delay, Runnable r);
}
