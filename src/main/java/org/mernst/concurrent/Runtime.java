package org.mernst.concurrent;

import com.google.auto.value.AutoValue;
import io.grpc.Context;
import io.grpc.Deadline;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.mernst.concurrent.AsyncSupplier.State.pull;

/**
 * The state machine of an ongoing evaluation which would be fairly simple except for IO and
 * cancellation.
 */
class Runtime {
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

  static <T> Context.CancellableContext start(
      AsyncSupplier<T> supplier,
      Consumer<Context.CancellableContext> onStart,
      BiConsumer<Context.CancellableContext, T> onValue,
      BiConsumer<Context.CancellableContext, Throwable> onFailure,
      Duration deadline,
      ScheduledExecutorService scheduler) {
    return start(
        supplier,
        onStart,
        onValue,
        onFailure,
        Context.current()
            .withDeadline(Deadline.after(deadline.toNanos(), TimeUnit.NANOSECONDS), scheduler),
        scheduler);
  }

  static <T> Context.CancellableContext start(
      AsyncSupplier<T> supplier,
      Consumer<Context.CancellableContext> onStart,
      BiConsumer<Context.CancellableContext, T> onValue,
      BiConsumer<Context.CancellableContext, Throwable> onFailure,
      ScheduledExecutorService scheduler) {
    return start(
        supplier, onStart, onValue, onFailure, Context.current().withCancellation(), scheduler);
  }

  private static <T> Context.CancellableContext start(
      AsyncSupplier<T> supplier,
      Consumer<Context.CancellableContext> onStart,
      BiConsumer<Context.CancellableContext, T> onValue,
      BiConsumer<Context.CancellableContext, Throwable> onFailure,
      Context.CancellableContext context,
      ScheduledExecutorService scheduler) {
    context.addListener(
        c -> {
          if (c.cancellationCause() != FINISHED_REGULARLY) {
            onFailure.accept(context, c.cancellationCause());
          }
        },
        scheduler);
    onStart.accept(context);
    context.run(
        () ->
            run(
                pull(
                    supplier,
                    t -> {
                      finish(context, onValue, t);
                      return null;
                    },
                    t -> {
                      finish(context, onFailure, t);
                      return null;
                    }),
                scheduler));
    return context;
  }

  static void run(AsyncSupplier.State state, ScheduledExecutorService scheduler) {
    while (state != null && !Context.current().isCancelled()) {
      if (state instanceof Pull) {
        state = checkNotNull(transitionFrom((Pull<?>) state));
      } else if (state instanceof Push) {
        state = transitionFrom((Push<?>) state);
      } else if (state instanceof StartIo) {
        state = transitionFrom((StartIo<?>) state, scheduler);
      }
    }
  }

  static <T> AsyncSupplier.State transitionFrom(Pull<T> state) {
    return state.supplier().eval(t -> push(state.onValue(), t), t -> push(state.onFailure(), t));
  }

  static <T> AsyncSupplier.State transitionFrom(Push<T> state) {
    return state.receiver().receive(state.value());
  }

  static <T> AsyncSupplier.State transitionFrom(
      StartIo<T> state, ScheduledExecutorService scheduler) {
    // We need one level of indirection to maintain listener identity.
    AtomicReference<Runnable> ioOp = new AtomicReference<>();
    Context.CancellationListener listener =
        context -> Optional.ofNullable(ioOp.get()).ifPresent(Runnable::run);
    Context current = Context.current();
    current.addListener(listener, scheduler);
    try {
      ioOp.set(
          state
              .io()
              .start(
                  scheduler,
                  recipe ->
                      current.run(
                          () -> {
                            current.removeListener(listener);
                            run(pull(recipe.impl, state.onSuccess(), state.onError()), scheduler);
                          })));
      // We are done, someone else will pick up the baton after us.
      return null;
    } catch (Throwable throwable) {
      // starting IO failed, rethrow.
      current.removeListener(listener);
      return push(state.onError(), throwable);
    }
  }

  static <T> void finish(
      Context.CancellableContext context, BiConsumer<Context.CancellableContext, T> consumer, T t) {
    if (context.cancel(FINISHED_REGULARLY)) {
      consumer.accept(context, t);
    }
  }

  /** Calls onValue and continues with its result. */
  private static <T> AsyncSupplier.State push(AsyncSupplier.Receiver<T> onValue, T value) {
    return new AutoValue_Runtime_Push<>(onValue, value);
  }

  private static final Throwable FINISHED_REGULARLY = new Throwable();
}
