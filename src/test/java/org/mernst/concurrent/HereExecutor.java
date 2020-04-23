package org.mernst.concurrent;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class HereExecutor implements ScheduledExecutorService {
  Instant start = Instant.now();
  Instant now = start;

  // Need: ordering by time and insertion order (to avoid starvation).
  Multimap<Instant, Runnable> todo = Multimaps.newMultimap(new TreeMap<>(), ArrayList::new);

  static class Cancellable<T> implements Runnable, ScheduledFuture<Void> {
    private final Runnable body;
    volatile boolean cancelled;

    Cancellable(Runnable body) {
      this.body = body;
    }

    @Override
    public boolean cancel(boolean b) {
      if (cancelled) {
        return false;
      }
      cancelled = true;
      return true;
    }

    @Override
    public void run() {
      if (!cancelled) body.run();
    }

    @Override
    public long getDelay(TimeUnit timeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Delayed delayed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCancelled() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDone() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Void get() throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Void get(long l, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public void execute(Runnable runnable) {
    at(now, runnable);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable r, long l, TimeUnit timeUnit) {
    Cancellable cancellable = new Cancellable(r);
    at(now.plus(Duration.ofNanos(TimeUnit.NANOSECONDS.convert(l, timeUnit))), cancellable);
    return cancellable;
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

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long l, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long l, long l1, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable runnable, long l, long l1, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void shutdown() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Runnable> shutdownNow() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isShutdown() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isTerminated() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> Future<T> submit(Callable<T> callable) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> Future<T> submit(Runnable runnable, T t) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Future<?> submit(Runnable runnable) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> collection) throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> collection, long l, TimeUnit timeUnit) throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> collection) throws InterruptedException, ExecutionException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> collection, long l, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
    throw new UnsupportedOperationException();
  }
}
