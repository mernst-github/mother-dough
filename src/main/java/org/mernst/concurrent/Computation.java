package org.mernst.concurrent;

import com.google.auto.value.AutoValue;
import io.grpc.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.mernst.concurrent.AsyncSupplier.State.pull;

/**
 * The state machine of an ongoing evaluation which would be fairly simple except for IO and
 * cancellation.
 */
class Computation<T> {
  private final Executor executor;
  final AtomicReference<io.grpc.Context.CancellableContext> context = new AtomicReference<>();
  private final ScheduledExecutorService shim;

  @AutoValue
  abstract static class Pull<T> implements AsyncSupplier.State {
    abstract AsyncSupplier<T> supplier();

    abstract AsyncSupplier.Receiver<T> onValue();

    abstract AsyncSupplier.Receiver<Throwable> onFailure();
  }

  @AutoValue
  abstract static class Push<T> implements AsyncSupplier.State {
    abstract AsyncSupplier.Receiver<T> receiver();

    // TODO: find out which "official" Nullable to get from where.
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    @Nullable
    abstract T value();
  }

  @AutoValue
  abstract static class StartIo<T> implements AsyncSupplier.State {
    abstract Recipe.IO<T> io();

    abstract AsyncSupplier.Receiver<T> onSuccess();

    abstract AsyncSupplier.Receiver<Throwable> onError();
  }

  public Computation(Executor executor) {
    this.executor = executor;
    this.shim = createShim();
  }

  public Executor.Cancellable start(
      AsyncSupplier<T> supplier,
      Consumer<T> onValue,
      Consumer<Throwable> onFailure,
      Duration deadline) {
    return start(
        supplier,
        onValue,
        onFailure,
        Context.current().withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS, shim));
  }

  public Executor.Cancellable start(
      AsyncSupplier<T> supplier, Consumer<T> onValue, Consumer<Throwable> onFailure) {
    return start(supplier, onValue, onFailure, Context.current().withCancellation());
  }

  private Executor.Cancellable start(
      AsyncSupplier<T> supplier,
      Consumer<T> onValue,
      Consumer<Throwable> onFailure,
      Context.CancellableContext context) {
    context.addListener(c -> finish(onFailure, c.cancellationCause()), executor);
    this.context.set(context);
    run(
        pull(
            supplier,
            t -> {
              finish(onValue, t);
              return null;
            },
            t -> {
              finish(onFailure, t);
              return null;
            }));
    return () -> context.cancel(null);
  }

  public void run(AsyncSupplier.State s) {
    Optional.ofNullable(context.get())
        .ifPresent(
            c ->
                c.run(
                    () -> {
                      AsyncSupplier.State state = s;
                      while (state != null && context.get() != null) {
                        if (state instanceof Pull) {
                          state = checkNotNull(transitionFrom((Pull<?>) state));
                        } else if (state instanceof Push) {
                          state = transitionFrom((Push<?>) state);
                          if (state == null) {
                            cancel(null);
                          }
                        } else if (state instanceof StartIo) {
                          state = transitionFrom((StartIo<?>) state);
                        }
                      }
                    }));
  }

  <T> AsyncSupplier.State transitionFrom(Pull<T> state) {
    return state.supplier().eval(t -> push(state.onValue(), t), t -> push(state.onFailure(), t));
  }

  <T> AsyncSupplier.State transitionFrom(Push<T> state) {
    return state.receiver().receive(state.value());
  }

  public <T> AsyncSupplier.State transitionFrom(StartIo<T> state) {
    // We need one level of indirection to maintain listener identity.
    AtomicReference<Executor.Cancellable> ioOp = new AtomicReference<>();
    Context.CancellationListener listener =
        context -> Optional.ofNullable(ioOp.get()).ifPresent(Executor.Cancellable::cancel);
    Context current = Context.current();
    current.addListener(listener, executor);
    try {
      ioOp.set(
          state
              .io()
              .start(
                  executor,
                  recipe ->
                      executor.execute(
                          current.wrap(
                              () -> {
                                current.removeListener(listener);
                                run(pull(recipe.impl, state.onSuccess(), state.onError()));
                              }))));
      // We are done, someone else will pick up the baton after us.
      return null;
    } catch (Throwable throwable) {
      // starting IO failed, rethrow.
      current.removeListener(listener);
      return push(state.onError(), throwable);
    }
  }

  public <T> void finish(Consumer<T> consumer, T t) {
    Optional.ofNullable(context.getAndSet(null))
        .ifPresent(
            c -> {
              consumer.accept(t);
              c.cancel(null);
            });
  }

  void cancel(Throwable cause) {
    Optional.ofNullable(context.get()).ifPresent(c -> c.cancel(cause));
  }

  /** Calls onValue and continues with its result. */
  private static <T> AsyncSupplier.State push(AsyncSupplier.Receiver<T> onValue, T value) {
    return new AutoValue_Computation_Push<>(onValue, value);
  }

  ScheduledExecutorService createShim() {
    try {
      Method schedule =
          ScheduledExecutorService.class.getMethod(
              "schedule", Runnable.class, long.class, TimeUnit.class);
      Method cancel = ScheduledFuture.class.getMethod("cancel", boolean.class);
      return (ScheduledExecutorService)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class[] {ScheduledExecutorService.class},
              (Object o, Method method, Object[] objects) -> {
                if (!method.equals(schedule)) {
                  throw new UnsupportedOperationException();
                }
                Executor.Cancellable cancellable =
                    executor.scheduleAfter(
                        Duration.ofNanos(
                            TimeUnit.NANOSECONDS.convert((long) objects[1], (TimeUnit) objects[2])),
                        (Runnable) objects[0]);
                return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[] {ScheduledFuture.class},
                    (o1, method1, objects1) -> {
                      if (!method1.equals(cancel)) {
                        throw new UnsupportedOperationException();
                      }
                      cancellable.cancel();
                      return true;
                    });
              });
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
