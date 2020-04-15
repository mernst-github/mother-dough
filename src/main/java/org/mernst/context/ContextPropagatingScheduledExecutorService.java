package org.mernst.context;

import com.google.common.collect.Collections2;
import io.grpc.Context;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class ContextPropagatingScheduledExecutorService implements ScheduledExecutorService {
  private final ScheduledExecutorService delegate;

  public ContextPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
    this.delegate = delegate;
  }

  @Override
  public void execute(Runnable runnable) {
    delegate.execute(propagating(runnable));
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable runnable, long l, TimeUnit timeUnit) {
    return delegate.schedule(propagating(runnable), l, timeUnit);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long l, TimeUnit timeUnit) {
    return delegate.schedule(propagating(callable), l, timeUnit);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable runnable, long l, long l1, TimeUnit timeUnit) {
    return delegate.scheduleAtFixedRate(propagating(runnable), l, l1, timeUnit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable runnable, long l, long l1, TimeUnit timeUnit) {
    return delegate.scheduleWithFixedDelay(propagating(runnable), l, l1, timeUnit);
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException {
    return delegate.awaitTermination(l, timeUnit);
  }

  @Override
  public <T> Future<T> submit(Callable<T> callable) {
    return delegate.submit(propagating(callable));
  }

  @Override
  public <T> Future<T> submit(Runnable runnable, T t) {
    return delegate.submit(propagating(runnable), t);
  }

  @Override
  public Future<?> submit(Runnable runnable) {
    return delegate.submit(propagating(runnable));
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> collection)
      throws InterruptedException {
    return delegate.invokeAll(propagating(collection));
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> collection, long l, TimeUnit timeUnit)
      throws InterruptedException {
    return delegate.invokeAll(propagating(collection), l, timeUnit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> collection)
      throws InterruptedException, ExecutionException {
    return delegate.invokeAny(propagating(collection));
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> collection, long l, TimeUnit timeUnit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.invokeAny(propagating(collection), l, timeUnit);
  }

  private <T> Collection<Callable<T>> propagating(Collection<? extends Callable<T>> collection) {
    return Collections2.transform(collection, this::propagating);
  }

  private Runnable propagating(Runnable r) {
    return Context.current()
        .wrap(
            () -> {
              try {
                r.run();
              } catch (Throwable t) {
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
              }
            });
  }

  private <T> Callable<T> propagating(Callable<T> s) {
    return Context.current().wrap(s);
  }
}
