package org.mernst.concurrent;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class RecipeTest {
  HereExecutor pool = new HereExecutor();

  @Test
  public void basic() {
    assertSame(this, eval(Recipe.to(this)));
    assertSame(this, eval(Recipe.from(() -> this)));
    assertSame(TEST_FAILURE_CLASS, failureType(Recipe.failed(TEST_FAILURE)));
  }

  @Test
  public void delayed() {
    assertEquals(
        12,
        eval(Recipe.to(1)
                .map(
                    i -> {
                      assertEquals(Duration.ofSeconds(15), pool.elapsed());
                      return 2 * i;
                    })
                .after(Duration.ofSeconds(10))
                .map(
                    i -> {
                      assertEquals(Duration.ofSeconds(15), pool.elapsed());
                      return 10 + i;
                    })
                .after(Duration.ofSeconds(5)))
            .intValue());
  }

  @Test
  public void failures() {
    assertSame(TEST_FAILURE_CLASS, failureType(Recipe.failed(TEST_FAILURE)));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Recipe.from(
                () -> {
                  throw TEST_FAILURE;
                })));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Recipe.to(this)
                .map(
                    ignore -> {
                      throw TEST_FAILURE;
                    })));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(Recipe.to(this).flatMap(ignore -> Recipe.failed(TEST_FAILURE))));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Recipe.to(this)
                .flatMap(
                    ignore -> {
                      throw TEST_FAILURE;
                    })));
  }

  @Test
  public void catching() {
    assertSame(
        TEST_FAILURE_CLASS,
        eval(
            Recipe.failed(TEST_FAILURE)
                .mapFailure(TEST_FAILURE_CLASS, t -> Optional.of(t.getClass()))));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Recipe.failed(TEST_FAILURE)
                .mapFailure(AssertionError.class, t -> Optional.of(t.getClass()))));
    Throwable failure =
        failure(
            Recipe.failed(new ArrayIndexOutOfBoundsException())
                .mapFailure(
                    t -> {
                      throw new AssertionError();
                    }));
    assertSame(AssertionError.class, failure.getClass());
    assertSame(ArrayIndexOutOfBoundsException.class, failure.getSuppressed()[0].getClass());
  }

  @Test
  public void retries() {
    AtomicInteger attempts = new AtomicInteger();
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Recipe.from(
                    () -> {
                      attempts.incrementAndGet();
                      throw TEST_FAILURE;
                    })
                .retrying(() -> IntStream.range(0, 10000).mapToObj(Duration::ofSeconds))));
    assertEquals(10000 + 1, attempts.get());
    assertEquals(Duration.ofSeconds(10000 * (10000 - 1) / 2), pool.elapsed());
  }

  @Test
  public void retries_withPredicate() {
    AtomicInteger attempts = new AtomicInteger();
    assertEquals(
        Status.INVALID_ARGUMENT,
        ((StatusRuntimeException)
                (failure(
                    Recipe.from(
                            () -> {
                              throw (attempts.getAndIncrement() < 1000)
                                  ? new StatusRuntimeException(Status.UNAVAILABLE)
                                  : new StatusRuntimeException(Status.INVALID_ARGUMENT);
                            })
                        .retryingOn(
                            StatusRuntimeException.class,
                            f -> f.getStatus().getCode() == Status.Code.UNAVAILABLE,
                            () -> Stream.generate(() -> Duration.ZERO)))))
            .getStatus());
    assertEquals(1001, attempts.get());
    assertEquals(Duration.ZERO, pool.elapsed());
  }

  @Test
  public void accumulate_delays() {
    Recipe<Duration> elapsed = Recipe.from(pool::elapsed);
    assertEquals(
        Stream.of(2, 5, 7).map(Duration::ofSeconds).collect(toImmutableList()),
        eval(
            Parallel.of(
                    () ->
                        java.util.stream.Stream.of(7, 2, 5)
                            .map(Duration::ofSeconds)
                            .map(elapsed::after))
                .accumulate(toImmutableList())));
  }

  @Test
  public void accumulate_delays_parallelism() {
    Recipe<Duration> elapsed = Recipe.from(pool::elapsed);
    assertEquals(
        Stream.of(2, 7, 7).map(Duration::ofSeconds).collect(toImmutableList()),
        eval(
            Parallel.of(
                    () ->
                        java.util.stream.Stream.of(7, 2, 5)
                            .map(Duration::ofSeconds)
                            .map(elapsed::after))
                .parallelism(2)
                .accumulate(toImmutableList())));
  }

  @Test
  public void accumulate_delays_no_parallelism() {
    Recipe<Duration> elapsed = Recipe.from(pool::elapsed);
    assertEquals(
        Stream.of(7, 9, 14).map(Duration::ofSeconds).collect(toImmutableList()),
        eval(
            Parallel.of(() -> Stream.of(7, 2, 5).map(Duration::ofSeconds).map(elapsed::after))
                .parallelism(1)
                .accumulate(toImmutableList())));
  }

  @Test
  public void accumulate_terminate() {
    Recipe<Optional<Integer>> r =
        Parallel.of(() -> IntStream.iterate(0, i -> i + 1).mapToObj(i -> Recipe.from(() -> i)))
            .parallelism(100)
            .firstMatching(i -> i == 10);
    assertEquals(10, eval(r).get().longValue());
  }

  @Test
  public void accumulate_failures() {
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Parallel.of(Recipe.to(1), Recipe.to(2), Recipe.failed(TEST_FAILURE))
                .accumulate(() -> 0, Integer::sum, i -> false, i -> i)));
    assertEquals(
        Integer.valueOf(3),
        eval(
            Parallel.of(Recipe.to(1), Recipe.to(2), Recipe.failed(TEST_FAILURE))
                .ignoring(TEST_FAILURE_CLASS)
                .accumulate(() -> 0, Integer::sum, i -> false, i -> i)));
    assertSame(
        TEST_FAILURE_CLASS,
        failureType(
            Parallel.of(Recipe.to(1), Recipe.to(2), Recipe.failed(TEST_FAILURE))
                .ignoring(TEST_FAILURE_CLASS, f -> f != TEST_FAILURE)
                .accumulate(() -> 0, Integer::sum, i -> false, i -> i)));
  }

  @Test
  public void accumulate_ignoreFailures() {}

  @Test
  public void flatten() {
    assertEquals(
        ImmutableList.of("one", "two", "three"),
        eval(
            Parallel.of(
                    Recipe.to("one"),
                    Recipe.to("two").after(Duration.ofSeconds(1)),
                    Recipe.to("three"))
                .inOrder()));
  }

  static class Slot {
    boolean set = false;

    <T, U> U set(T value) {
      set = true;
      return null;
    }

    <U> U set() {
      return set(null);
    }
  }

  @Test
  public void cancel() {
    // Can't cancel current context so we need this clutch.
    AtomicReference<Context.CancellableContext> context = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Recipe<Void> recipe =
        Recipe.io(
            (c, whenDone) -> {
              context.get().cancel(new CancellationException());
              whenDone.accept(Recipe.to(null));
              return null;
            });
    Runtime.start(recipe.impl, context::set, (ctx, value) -> {}, (ctx, f) -> failure.set(f), pool);
    pool.run();
    assertSame(CancellationException.class, failure.get().getClass());
  }

  @Test
  public void deadline() {
    assertSame(
        this,
        eval(
            Recipe.from(() -> this)
                .after(Duration.ofSeconds(3))
                .withDeadline(Duration.ofSeconds(5))));

    assertSame(
        this,
        eval(
            Recipe.from(() -> this)
                .withDeadline(Duration.ofSeconds(3))
                .after(Duration.ofSeconds(5))));
  }

  @Test
  public void deadline_triggers() {
    Slot result = new Slot();
    assertSame(
        StatusRuntimeException.class,
        failureType(
            Recipe.from(result::set)
                .after(Duration.ofSeconds(5))
                .withDeadline(Duration.ofSeconds(3))));
    assertFalse("Should have been cancelled", result.set);
  }

  @Test
  public void hedge() {
    // TODO: assert that second recipe was scheduled, that we're seeing the first result, the second
    // was cancelled, ... only seen this in debug output so far.
    assertSame(
        this,
        eval(
            Recipe.from(() -> this)
                .after(Duration.ofSeconds(5))
                .hedgingWith(Duration.ofSeconds(5))));
  }

  private <T> T eval(Recipe<T> recipe) {
    ListenableFuture<T> future = recipe.evaluate(pool);
    pool.run();
    assertTrue(future.isDone());
    return Futures.getUnchecked(future);
  }

  private <T> Throwable failure(Recipe<T> recipe) {
    ListenableFuture<Throwable> future =
        Futures.catching(
            Futures.transform(recipe.evaluate(pool), value -> null, pool),
            Throwable.class,
            t -> t,
            pool);
    pool.run();
    assertTrue(future.isDone());
    return checkNotNull(Futures.getUnchecked(future));
  }

  private Class<? extends Throwable> failureType(Recipe<?> recipe) {
    return failure(recipe).getClass();
  }

  private static RuntimeException TEST_FAILURE = new RuntimeException() {};
  private static Class<? extends Throwable> TEST_FAILURE_CLASS = TEST_FAILURE.getClass();
}
