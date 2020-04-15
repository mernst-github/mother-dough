package org.mernst.concurrent;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class HereExecutor implements Executor {
  Instant start = Instant.now();
  Instant now = start;
  ImmutableList.Builder<Map.Entry<Instant, Runnable>> todo = ImmutableList.builder();

  static class Cancellable implements Runnable {
    private final Runnable body;
    volatile boolean cancelled;

    Cancellable(Runnable body) {
      this.body = body;
    }

    void cancel() {
      cancelled = true;
    }

    @Override
    public void run() {
      if (!cancelled) body.run();
    }
  }

  @Override
  public void execute(Runnable runnable) {
    at(now, runnable);
  }

  @Override
  public Executor.Cancellable scheduleAfter(Duration delay, Runnable r) {
    Cancellable cancellable = new Cancellable(r);
    at(now.plus(delay), cancellable);
    return cancellable::cancel;
  }

  private void at(Instant time, Runnable runnable) {
    todo.add(Maps.immutableEntry(time, runnable));
  }

  public Duration elapsed() {
    return Duration.between(start, now);
  }

  public void run() {
    ImmutableList<Map.Entry<Instant, Runnable>> jobs;
    while (!(jobs = todo.build()).isEmpty()) {
      todo = ImmutableList.builder();
      jobs.stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              e -> {
                now = e.getKey();
                e.getValue().run();
              });
    }
  }
}
