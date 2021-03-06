package org.mernst.concurrent;

import com.google.common.collect.Streams;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.mernst.collect.Streamable;
import org.mernst.functional.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.mernst.concurrent.Runtime.State.pull;
import static org.mernst.concurrent.Runtime.State.startIo;
import static org.mernst.concurrent.Runtime.start;

public final class Recipe<T> {
  final Runtime.AsyncSupplier<T> impl;

  Recipe(Runtime.AsyncSupplier<T> impl) {
    this.impl = impl;
  }

  static <T> Recipe<T> wrap(Runtime.AsyncSupplier<T> impl) {
    return new Recipe<>(impl);
  }

  public static <T> Recipe<T> io(IO<T> io) {
    return wrap((onValue, onFailure) -> startIo(io, onValue, onFailure));
  }

  public static <T> Recipe<T> fromFuture(ThrowingSupplier<? extends ListenableFuture<T>> f) {
    return io(
        (scheduler, whenDone) -> {
          ListenableFuture<T> future = f.get();
          Futures.addCallback(
              future,
              new FutureCallback<T>() {
                @Override
                public void onSuccess(T value) {
                  whenDone.accept(to(value));
                }

                @Override
                public void onFailure(Throwable failure) {
                  whenDone.accept(failed(failure));
                }
              },
              scheduler);
          return () -> future.cancel(false);
        });
  }

  public static <T> Recipe<T> to(T t) {
    return wrap((onValue, onFailure) -> onValue.receive(t));
  }

  public static <T> Recipe<T> failed(Throwable t) {
    return wrap((onValue, onFailure) -> onFailure.receive(t));
  }

  public static <T> Recipe<T> from(ThrowingSupplier<T> s) {
    return to(s).map(ThrowingSupplier::get);
  }

  public <U> Recipe<U> map(ThrowingFunction<T, U> mapping) {
    return wrap(
        (onValue, onFailure) ->
            pull(
                impl,
                value -> {
                  U mapped;
                  try {
                    mapped = mapping.apply(value);
                  } catch (Throwable failure) {
                    return onFailure.receive(failure);
                  }
                  return onValue.receive(mapped);
                },
                onFailure));
  }

  public Recipe<T> mapFailure(ThrowingFunction<Throwable, T> failureMapping) {
    return mapFailure(Throwable.class, f -> Optional.ofNullable(failureMapping.apply(f)));
  }

  public <F extends Throwable> Recipe<T> mapFailure(
      Class<F> failureType, ThrowingFunction<F, Optional<T>> failureMapping) {
    return wrap(
        (onValue, onFailure) ->
            pull(
                impl,
                onValue,
                failure -> {
                  Optional<T> mapped;
                  try {
                    mapped =
                        failureType.isInstance(failure)
                            ? failureMapping.apply(failureType.cast(failure))
                            : Optional.empty();
                  } catch (Throwable t) {
                    t.addSuppressed(failure);
                    return onFailure.receive(t);
                  }
                  return mapped.map(onValue::receive).orElseGet(() -> onFailure.receive(failure));
                }));
  }

  public <U> Recipe<U> flatMap(ThrowingFunction<T, ? extends Recipe<U>> continuation) {
    return flatten(map(continuation));
  }

  public <F extends Throwable> Recipe<T> flatMapFailure(
      Class<F> failureType, ThrowingFunction<F, Recipe<T>> failureMapping) {
    return flatten(
        map(Recipe::to).mapFailure(failureType, f -> Optional.of(failureMapping.apply(f))));
  }

  private static <T> Recipe<T> flatten(Recipe<? extends Recipe<T>> rr) {
    return wrap(
        (onValue, onFailure) -> pull(rr.impl, r -> pull(r.impl, onValue, onFailure), onFailure));
  }

  public Recipe<T> flatMapFailure(ThrowingFunction<Throwable, Recipe<T>> failureMapping) {
    return flatMapFailure(Throwable.class, failureMapping);
  }

  public Recipe<T> afterwards(ThrowingConsumer<T> onSuccess, ThrowingConsumer<Throwable> onError) {
    return wrap(
        (onValue, onFailure) ->
            pull(
                this.impl,
                t -> {
                  try {
                    onSuccess.accept(t);
                  } catch (Throwable failure) {
                    return onFailure.receive(failure);
                  }
                  return onValue.receive(t);
                },
                f -> {
                  try {
                    onError.accept(f);
                  } catch (Throwable failure) {
                    f.addSuppressed(failure);
                  }
                  return onFailure.receive(f);
                }));
  }

  public Recipe<T> afterwards(ThrowingRunnable body) {
    return afterwards(value -> body.run(), failure -> body.run());
  }

  public Plan consume(ThrowingFunction<T, ? extends Plan> consumer) {
    return Plan.from(flatMap(v -> consumer.apply(v).asRecipe()));
  }

  public Recipe<T> after(Duration delay) {
    return io(
        (scheduler, whenDone) -> {
          ScheduledFuture<?> f =
              scheduler.schedule(
                  () -> whenDone.accept(this), delay.toNanos(), TimeUnit.NANOSECONDS);
          return () -> f.cancel(false);
        });
  }

  public <F extends Throwable> Recipe<T> retryingOn(
      Class<F> failure, ThrowingPredicate<F> p, Streamable<Duration> delays) {
    Iterator<Duration> delayIt = delays.stream().iterator();
    return flatMapFailure(
        failure,
        f ->
            delayIt.hasNext() && p.test(f)
                ? retryingOn(failure, p, () -> Streams.stream(delayIt))
                    .after(delayIt.next())
                    .flatMapFailure(
                        finalFailure -> {
                          finalFailure.addSuppressed(f);
                          return failed(finalFailure);
                        })
                : failed(f));
  }

  public Recipe<T> retrying(Streamable<Duration> delays) {
    return retryingOn(Throwable.class, f -> true, delays);
  }

  public Recipe<T> withDeadline(Duration deadline) {
    // (Ab?)using io to start "this" in a new context w/ deadline.
    return Recipe.<T>io(
            (scheduler, whenDone) -> {
              start(
                  impl,
                  ctx -> {},
                  (ctx, t) -> whenDone.accept(to(t)),
                  (ctx, t) -> whenDone.accept(failed(t)),
                  deadline,
                  scheduler);
              return () -> {};
            })
        .flatMapFailure(
            TimeoutException.class,
            t ->
                Recipe.failed(
                    new StatusRuntimeException(
                        Status.DEADLINE_EXCEEDED
                            .withDescription(deadline.toString())
                            .withCause(t))));
  }

  static class ResultOrFailure<T> {
    T result;
    Throwable failure;

    boolean isSuccess() {
      return failure == null;
    }

    static <T> ResultOrFailure<T> result(T t) {
      ResultOrFailure<T> result = new ResultOrFailure<>();
      result.result = t;
      return result;
    }

    static <T> ResultOrFailure<T> failure(Throwable failure) {
      ResultOrFailure<T> result = new ResultOrFailure<>();
      result.failure = failure;
      return result;
    }

    static <T> ResultOrFailure<T> combine(ResultOrFailure<T> left, ResultOrFailure<T> right) {
      if (right.isSuccess()) return right;
      if (left.failure == null) return right;
      left.failure.addSuppressed(right.failure);
      return left;
    }
  }

  public Recipe<T> hedgingWith(Duration delay) {
    // Don't let one failure fail the entire hedged call.
    Recipe<ResultOrFailure<T>> catching =
        this.map(ResultOrFailure::result).mapFailure(ResultOrFailure::failure);
    return Parallel.of(() -> Stream.of(catching, catching.after(delay)))
        .<ResultOrFailure<T>, T>accumulate(
            ResultOrFailure::new,
            (left, right) -> right,
            /* terminator= */ ResultOrFailure::isSuccess,
            /* finisher= */ r -> {
              if (r.isSuccess()) return r.result;
              throw r.failure;
            });
  }

  public static class Results {
    private final HashMap<Recipe<?>, Object> values = new HashMap<>();

    <T> T get(Recipe<T> key) {
      return (T) values.get(key);
    }
  }

  public static Recipe<Results> from(Recipe<?>... recipes) {
    return Parallel.of(() -> Arrays.stream(recipes).map(r -> r.map(o -> (Object) o)))
        .inOrder()
        .map(
            list -> {
              Results results = new Results();
              for (int i = 0; i < recipes.length; i++) {
                results.values.put(recipes[i], list.get(i));
              }
              return results;
            });
  }

  /**
   * An IO operation suspends the recipe evaluation in favor of its own and eventually supplies a
   * replacement Recipe. It *can* use the provided scheduler if necessary and it *can* return a
   * cancellation callback if it doesn't listen to cancellation of the surrounding Context, in this
   * case the framework will attach the callback as context listener for the lifetime of the IO op.
   */
  public interface IO<T> {
    /** the evaluation has been cancelled, stop the io operation. */
    interface CancellationCallback {
      void run();
    }

    CancellationCallback start(ScheduledExecutorService scheduler, Consumer<Recipe<T>> whenDone)
        throws Throwable;
  }

  public ListenableFuture<T> evaluate(ScheduledExecutorService scheduler) {
    SettableFuture<T> result = SettableFuture.create();
    Context.CancellableContext context =
        start(
            impl,
            ctx -> {},
            (ctx, value) -> result.set(value),
            (ctx, failure) -> result.setException(failure),
            scheduler);
    result.addListener(
        () -> {
          if (result.isCancelled()) {
            context.cancel(null);
          }
        },
        scheduler);
    return result;
  }
}
