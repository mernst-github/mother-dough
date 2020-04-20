package org.mernst.concurrent;

import com.google.common.util.concurrent.ListenableFuture;
import org.mernst.collect.Streamable;
import org.mernst.functional.ThrowingConsumer;
import org.mernst.functional.ThrowingFunction;
import org.mernst.functional.ThrowingRunnable;
import org.mernst.functional.ThrowingSupplier;

import java.util.Iterator;

/** A better api for a Recipe&lt;Void&gt;. */
public final class Plan {
  private static final Plan NONE = new Plan((onValue, onFailure) -> onValue.receive(null));

  final AsyncSupplier<Void> impl;

  private Plan(AsyncSupplier<Void> impl) {
    this.impl = impl;
  }

  public ListenableFuture<Void> start(Executor e) {
    return asRecipe().evaluate(e);
  }

  public static Plan from(Recipe<Void> recipe) {
    return new Plan(recipe.impl);
  }

  public Recipe<Void> asRecipe() {
    return Recipe.wrap(impl);
  }

  public static Plan of(ThrowingRunnable r) {
    return from(
        Recipe.from(
            () -> {
              r.run();
              return null;
            }));
  }

  public static Plan of(ThrowingRunnable... r) {
    return of(Streamable.of(r));
  }

  public static Plan of(Streamable<? extends ThrowingRunnable> r) {
    return from(
        Recipe.from(
            () -> {
              for (Iterator<? extends ThrowingRunnable> it = r.stream().iterator();
                  it.hasNext(); ) {
                it.next().run();
              }
              return null;
            }));
  }

  public static Plan none() {
    return NONE;
  }

  public Plan then(ThrowingRunnable r) {
    return from(
        asRecipe()
            .map(
                aVoid -> {
                  r.run();
                  return null;
                }));
  }

  public <F extends Throwable> Plan exceptOn(Class<F> failureType, ThrowingConsumer<F> handler) {
    return from(
        asRecipe()
            .mapFailure(
                failureType,
                failure -> {
                  handler.accept(failure);
                  return java.util.Optional.empty();
                }));
  }

  public Plan except(ThrowingConsumer<Throwable> handler) {
    return exceptOn(Throwable.class, handler);
  }

  public Plan then(ThrowingSupplier<Plan> continuation) {
    return from(asRecipe().flatMap(aVoid -> continuation.get().asRecipe()));
  }

  public <F extends Throwable> Plan exceptThen(
      Class<F> failureType, ThrowingFunction<F, Plan> handler) {
    return from(asRecipe().flatMapFailure(failureType, f -> handler.apply(f).asRecipe()));
  }

  public Plan exceptThen(ThrowingFunction<Throwable, Plan> handler) {
    return exceptThen(Throwable.class, handler);
  }
}
