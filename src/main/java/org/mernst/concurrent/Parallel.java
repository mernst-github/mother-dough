package org.mernst.concurrent;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import io.grpc.Context;
import org.mernst.collect.Streamable;
import org.mernst.functional.*;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.mernst.concurrent.Runtime.State.startIo;

/** Accumulation support for multiple recipes. */
@AutoValue
public abstract class Parallel<T> {
  public abstract Streamable<Recipe<T>> inputs();

  public abstract int parallelism();

  public abstract ThrowingPredicate<Throwable> failureFilter();

  public static <T> Parallel<T> of(Streamable<Recipe<T>> inputs) {
    return new AutoValue_Parallel<>(inputs, Integer.MAX_VALUE, f -> false);
  }

  @SafeVarargs
  public static <T> Parallel<T> of(Recipe<T>... inputs) {
    return of(() -> Arrays.stream(inputs));
  }

  public Parallel<T> parallelism(int parallelism) {
    return new AutoValue_Parallel<>(inputs(), parallelism, failureFilter());
  }

  public Parallel<T> ignoringFailures() {
    return new AutoValue_Parallel<>(inputs(), parallelism(), f -> true);
  }

  public Parallel<T> ignoring(Class<? extends Throwable> c) {
    return new AutoValue_Parallel<>(inputs(), parallelism(), failureFilter().or(c::isInstance));
  }

  public <F> Parallel<T> ignoring(Class<F> c, ThrowingPredicate<F> filter) {
    return new AutoValue_Parallel<>(
        inputs(),
        parallelism(),
        failureFilter().or(o -> c.isInstance(o) && filter.test(c.cast(o))));
  }

  public Plan consumeInCompletionOrder(ThrowingConsumer<T> consumer) {
    return Plan.from(
        this.<Void, Void>accumulate(
            () -> null,
            (aVoid, t) -> {
              consumer.accept(t);
              return null;
            },
            a -> false,
            a -> a));
  }

  public Recipe<ImmutableList<T>> inOrder() {
    ImmutableList<Recipe<T>> recipeList = inputs().stream().collect(toImmutableList());
    return new AutoValue_Parallel<>(
            Indexed.from(recipeList::stream), parallelism(), failureFilter())
        .accumulate(
            () -> (T[]) new Object[recipeList.size()],
            (array, indexedValue) -> {
              array[indexedValue.index] = indexedValue.value;
              return array;
            },
            array -> false,
            ImmutableList::copyOf);
  }

  public Recipe<ImmutableList<T>> inCompletionOrder() {
    return this.<ImmutableList.Builder<T>, ImmutableList<T>>accumulate(
        ImmutableList::builder,
        ImmutableList.Builder::add,
        array -> false,
        ImmutableList.Builder::build);
  }

  public <A, U> Recipe<U> accumulate(Collector<T, A, U> collector) {
    return accumulate(collector, a -> false);
  }

  public <A, U> Recipe<U> accumulate(
      Collector<T, A, U> collector, ThrowingPredicate<A> terminator) {
    BiConsumer<A, T> accumulator = collector.accumulator();
    return accumulate(
        collector.supplier()::get,
        (a, t) -> {
          accumulator.accept(a, t);
          return a;
        },
        terminator,
        collector.finisher()::apply);
  }

  public <A, U> Recipe<U> accumulate(
      ThrowingSupplier<A> supplier,
      ThrowingBiFunction<A, T, A> accumulator,
      ThrowingPredicate<A> terminator,
      ThrowingFunction<A, U> finisher) {
    return Recipe.<A>wrap(
            (onValue, onFailure) -> {
              A zero;
              try {
                zero = supplier.get();
              } catch (Throwable t) {
                return onFailure.receive(t);
              }
              Iterator<Recipe<T>> inputs = inputs().stream().iterator();
              if (!inputs.hasNext()) {
                return onValue.receive(zero);
              }
              return startIo(
                  (computation, whenDone) ->
                      new Accumulation<>(
                              computation,
                              whenDone,
                              inputs,
                              parallelism(),
                              failureFilter(),
                              zero,
                              accumulator,
                              terminator)
                          .start(),
                  onValue,
                  onFailure);
            })
        .map(finisher);
  }

  public Recipe<Optional<T>> first() {
    return this.<Optional<T>, Optional<T>>accumulate(
        Optional::empty, (a, t) -> Optional.ofNullable(t), a -> true, a -> a);
  }

  public Recipe<Optional<T>> firstMatching(Predicate<T> predicate) {
    return this.<Optional<T>, Optional<T>>accumulate(
        Optional::empty,
        (a, t) -> Optional.ofNullable(t).filter(predicate),
        Optional::isPresent,
        a -> a);
  }
}

class Indexed<T> {
  final int index;
  final T value;

  Indexed(int index, T value) {
    this.index = index;
    this.value = value;
  }

  static <T> Streamable<Recipe<Indexed<T>>> from(Streamable<Recipe<T>> recipes) {
    return () ->
        Streams.zip(
            IntStream.iterate(0, i -> i + 1).boxed(),
            recipes.stream(),
            (index, recipe) -> recipe.map(v -> new Indexed<>(index, v)));
  }
}

class Accumulation<T, U> {
  private final ScheduledExecutorService scheduler;
  final Consumer<Recipe<U>> parent;
  final Iterator<Recipe<T>> inputs;
  int remainingParallelism;
  final ThrowingPredicate<Throwable> failureFilter;
  final ThrowingBiFunction<U, T, U> accumulator;
  final ThrowingPredicate<U> terminator;

  U accu;
  Throwable failure = null;
  boolean terminated = false;
  Set<Context.CancellableContext> running = new HashSet<>();

  public Accumulation(
      ScheduledExecutorService scheduler,
      Consumer<Recipe<U>> parent,
      Iterator<Recipe<T>> inputs,
      int parallelism,
      ThrowingPredicate<Throwable> failureFilter,
      U zero,
      ThrowingBiFunction<U, T, U> accumulator,
      ThrowingPredicate<U> terminator) {
    this.scheduler = scheduler;
    this.parent = parent;
    this.inputs = inputs;
    this.remainingParallelism = parallelism;
    this.failureFilter = failureFilter;
    this.accumulator = accumulator;
    this.terminator = terminator;
    this.accu = zero;
  }

  Recipe.IO.CancellationCallback start() {
    startOne();
    // Our child computations are cancelled via forking off the parent context, so we don't need to
    // implement propagation.
    return null;
  }

  private synchronized void startOne() {
    start(inputs.next());
    --remainingParallelism;
    if (remainingParallelism > 0 && inputs.hasNext() && !terminated) {
      startOne();
    }
  }

  public synchronized void onInputValue(Context.CancellableContext child, T inputValue) {
    running.remove(child);
    if (terminated || failure != null) {
      return;
    }
    try {
      accu = accumulator.apply(accu, inputValue);
      terminated = terminator.test(accu);
    } catch (Throwable failure) {
      this.failure = failure;
    }
    afterInput();
  }

  public synchronized void onInputFailure(
      Context.CancellableContext child, Throwable inputFailure) {
    running.remove(child);
    if (terminated || this.failure != null) {
      return;
    }
    try {
      if (!failureFilter.test(inputFailure)) {
        this.failure = inputFailure;
      }
    } catch (Throwable t) {
      inputFailure.addSuppressed(t);
      this.failure = inputFailure;
    }
    afterInput();
  }

  private void afterInput() {
    boolean notify = terminated || (failure != null);
    if (!notify) {
      if (inputs.hasNext()) {
        start(inputs.next());
      } else {
        notify = running.isEmpty();
      }
    }
    if (notify) queueNotification();
  }

  void queueNotification() {
    cancel();
    parent.accept((failure != null) ? Recipe.failed(failure) : Recipe.to(accu));
  }

  void start(Recipe<T> recipe) {
    Context current = Context.current();
    Runtime.start(
        recipe.impl,
        running::add,
        (context, value) -> current.run(() -> onInputValue(context, value)),
        (context, failure) -> current.run(() -> onInputFailure(context, failure)),
        scheduler);
  }

  private void cancel() {
    running.forEach(c -> c.cancel(null));
  }
}
