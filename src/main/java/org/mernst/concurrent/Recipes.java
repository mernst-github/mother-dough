package org.mernst.concurrent;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import org.mernst.collect.Streamable;
import org.mernst.functional.ThrowingBiFunction;
import org.mernst.functional.ThrowingFunction;
import org.mernst.functional.ThrowingPredicate;
import org.mernst.functional.ThrowingSupplier;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.mernst.concurrent.AsyncSupplier.State.*;

/**
 * Accumulation support for multiple recipes.
 */
public interface Recipes<T> extends Streamable<Recipe<T>> {
  default int parallelism() {
    return Integer.MAX_VALUE;
  }

  static <T> Recipes<T> of(Streamable<Recipe<T>> recipes) {
    return recipes::stream;
  }

  @SafeVarargs
  static <T> Recipes<T> of(Recipe<T>... recipes) {
    return () -> Arrays.stream(recipes);
  }

  default Recipes<T> parallelism(int parallelism) {
    Recipes<T> self = this;
    return new Recipes<T>() {
      @Override
      public Stream<Recipe<T>> stream() {
        return self.stream();
      }

      @Override
      public int parallelism() {
        return parallelism;
      }
    };
  }

  default Plan consumeInCompletionOrder(Plan.ThrowingConsumer<T> consumer) {
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

  default Recipe<ImmutableList<T>> inOrder() {
    ImmutableList<Recipe<T>> recipeList = stream().collect(toImmutableList());
    return Recipes.of(Indexed.from(recipeList::stream))
        .accumulate(
            () -> (T[]) new Object[recipeList.size()],
            (array, indexedValue) -> {
              array[indexedValue.index] = indexedValue.value;
              return array;
            },
            array -> false,
            ImmutableList::copyOf);
  }

  default Recipe<ImmutableList<T>> inCompletionOrder() {
    return this.<ImmutableList.Builder<T>, ImmutableList<T>>accumulate(
        ImmutableList::builder,
        ImmutableList.Builder::add,
        array -> false,
        ImmutableList.Builder::build);
  }

  default <A, U> Recipe<U> accumulate(Collector<T, A, U> collector) {
    return accumulate(collector, a -> false);
  }

  default <A, U> Recipe<U> accumulate(
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

  default <A, U> Recipe<U> accumulate(
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
                return push(onFailure, t);
              }
              Iterator<Recipe<T>> inputs = stream().iterator();
              if (!inputs.hasNext()) {
                return push(onValue, zero);
              }
              return startIo(
                  (executor, resumable) ->
                      new Accumulation<>(
                              executor,
                              resumable,
                              inputs,
                              parallelism(),
                              zero,
                              accumulator,
                              terminator,
                              onValue,
                              onFailure)
                          .start(),
                  onFailure);
            })
        .map(finisher);
  }

  default Recipe<Optional<T>> first() {
    return this.<Optional<T>, Optional<T>>accumulate(
        Optional::empty, (a, t) -> Optional.ofNullable(t), a -> true, a -> a);
  }

  default Recipe<Optional<T>> firstMatching(Predicate<T> predicate) {
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

class Accumulation<T, U> implements Executor.Cancellable {
  private final Executor executor;
  final AsyncSupplier.State.IO.Resumable parent;
  final Iterator<Recipe<T>> inputs;
  int parallelism;
  final ThrowingBiFunction<U, T, U> accumulator;
  final ThrowingPredicate<U> terminator;
  final AsyncSupplier.Receiver<U> onValue;
  final AsyncSupplier.Receiver<Throwable> onFailure;

  U accu;
  Throwable failure = null;
  boolean terminated = false;
  Set<Computation> running = new HashSet<>();

  public Accumulation(
      Executor executor,
      AsyncSupplier.State.IO.Resumable parent,
      Iterator<Recipe<T>> inputs,
      int parallelism,
      U zero,
      ThrowingBiFunction<U, T, U> accumulator,
      ThrowingPredicate<U> terminator,
      AsyncSupplier.Receiver<U> onValue,
      AsyncSupplier.Receiver<Throwable> onFailure) {
    this.executor = executor;
    this.parent = parent;
    this.inputs = inputs;
    this.parallelism = parallelism;
    this.accumulator = accumulator;
    this.terminator = terminator;
    this.onValue = onValue;
    this.onFailure = onFailure;
    this.accu = zero;
  }

  Accumulation start() {
    startOne();
    return this;
  }

  private synchronized void startOne() {
    start(inputs.next());
    --parallelism;
    if (parallelism > 0 && inputs.hasNext() && !terminated) {
      startOne();
    }
  }

  public synchronized AsyncSupplier.State onInputValue(Computation child, T inputValue) {
    running.remove(child);
    if (terminated || failure != null) {
      return null;
    }
    try {
      accu = accumulator.apply(accu, inputValue);
      terminated = terminator.test(accu);
    } catch (Throwable failure) {
      this.failure = failure;
    }
    boolean notify = terminated || (failure != null);
    if (!notify) {
      if (inputs.hasNext()) {
        start(inputs.next());
      } else {
        notify = running.isEmpty();
      }
    }
    if (notify) queueNotification();
    return null;
  }

  public synchronized AsyncSupplier.State onInputFailure(Computation child, Throwable failure) {
    running.remove(child);
    if (terminated || this.failure != null) {
      return null;
    }
    this.failure = failure;
    queueNotification();
    return null;
  }

  void queueNotification() {
    cancel();
    parent.resumeWith((failure != null) ? push(onFailure, failure) : push(onValue, accu));
  }

  void start(Recipe<T> recipe) {
    Computation child = new Computation(executor);
    running.add(child);
    executor.execute(
        () ->
            child.run(
                pull(
                    recipe.impl,
                    value -> onInputValue(child, value),
                    failure -> onInputFailure(child, failure))));
  }

  @Override
  public void cancel() {
    running.forEach(Computation::cancel);
  }
}
