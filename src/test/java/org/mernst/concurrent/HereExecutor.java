package org.mernst.concurrent;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class HereExecutor implements Executor {
  Instant start = Instant.now();
  Instant now = start;

  // Need: ordering by time and insertion order (to avoid starvation).
  Multimap<Instant, Runnable> todo = Multimaps.newMultimap(new TreeMap<>(), ArrayList::new);

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
    todo.put(time, runnable);
  }

  public Duration elapsed() {
    return Duration.between(start, now);
  }

  public void run() {
    while (!todo.isEmpty()) {
      removeFirst().forEach(Runnable::run);
    }
  }

  private ImmutableList<Runnable> removeFirst() {
    Iterator<Map.Entry<Instant, Collection<Runnable>>> it = todo.asMap().entrySet().iterator();
    Map.Entry<Instant, Collection<Runnable>> entry = it.next();
    now = entry.getKey();
    ImmutableList<Runnable> result = ImmutableList.copyOf(entry.getValue());
    it.remove();
    return result;
  }
}
