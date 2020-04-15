package org.mernst.concurrent;

import com.google.auto.value.AutoValue;
import org.mernst.functional.Failure;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.mernst.concurrent.AsyncSupplier.State.push;

/**
 * The state machine of an ongoing evaluation which would be fairly simple except for IO and
 * cancellation.
 */
class Computation implements Executor.Cancellable {
  private final Executor executor;

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
  abstract static class StartIo implements AsyncSupplier.State {
    abstract IO io();

    abstract AsyncSupplier.Receiver<Throwable> onError();
  }

  private static final Executor.Cancellable NONE =
      () -> {
        throw new IllegalStateException("Trying to cancel NONE");
      };

  private static final Executor.Cancellable CANCELLED =
      () -> {
        throw new IllegalStateException("Trying to cancel CANCELLED");
      };

  /**
   * State machine: NONE--suspend-->$cb. NONE--cancel-->CANCELLED. $cb--resume-->NONE.
   * $cb--cancel-->CANCELLED, cb gets called. CANCELLED--resume-->CANCELLED.
   * CANCELLED--suspend-->CANCELLED, cb gets called. CANCELLED--cancel-->CANCELLED.
   */
  private final AtomicReference<Executor.Cancellable> cancellationCb = new AtomicReference<>(NONE);

  public Computation(Executor executor) {
    this.executor = executor;
  }

  public void run(AsyncSupplier.State state) {
    while (state != null && !isCancelled()) {
      if (state instanceof Pull) {
        state = checkNotNull(transitionFrom((Pull<?>) state));
      } else if (state instanceof Push) {
        state = transitionFrom((Push<?>) state);
        if (state == null) {
          Executor.Cancellable current = cancellationCb.getAndSet(CANCELLED);
          checkState(current == CANCELLED || current == NONE, "dangling cb %s", current);
          break;
        }
      } else if (state instanceof StartIo) {
        state = transitionFrom((StartIo) state);
      }
    }
  }

  <T> AsyncSupplier.State transitionFrom(Pull<T> state) {
    return state.supplier().eval(state.onValue(), state.onFailure());
  }

  <T> AsyncSupplier.State transitionFrom(Push<T> state) {
    return state.receiver().receive(state.value());
  }

  public AsyncSupplier.State transitionFrom(StartIo state) {
    // Can't do that atomically, instead we go through two steps.
    // First, install a dummy, but unique cb value.
    Executor.Cancellable prepare = () -> {};
    if (this.cancellationCb.updateAndGet(
            l ->
                l == CANCELLED
                    ? CANCELLED
                    : l == NONE
                        ? prepare
                        : Failure.throwing(new IllegalStateException("already suspended")))
        == CANCELLED) {
      return null;
    }
    // That worked, now start the async activity and receive the cancellation cb.
    Executor.Cancellable cb;
    try {
      cb =
          state
              .io()
              .start(executor, resumptionState -> executor.execute(() -> resume(resumptionState)));
    } catch (Throwable throwable) {
      // starting IO failed, rethrow.
      this.cancellationCb.compareAndSet(prepare, NONE);
      return push(state.onError(), throwable);
    }
    // That worked, now register the actual cancellation callback.
    // However, we may have already been resumed, but then state would be != resuming.
    // In that case, skip the registration.
    if (this.cancellationCb.updateAndGet(l -> l == prepare ? cb : l) == CANCELLED) {
      cb.cancel();
    }
    // In any case, we are done, someone else will pick up the baton after us.
    return null;
  }

  public void resume(AsyncSupplier.State state) {
    this.cancellationCb.updateAndGet(
        l ->
            l == CANCELLED
                ? CANCELLED
                : l == NONE ? Failure.throwing(new IllegalStateException("not suspended")) : NONE);
    run(state);
  }

  boolean isCancelled() {
    return cancellationCb.get() == CANCELLED;
  }

  @Override
  public void cancel() {
    Executor.Cancellable cb = this.cancellationCb.getAndSet(CANCELLED);
    if (cb != CANCELLED && cb != NONE) executor.execute(cb::cancel);
  }
}
