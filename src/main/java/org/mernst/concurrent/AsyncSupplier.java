package org.mernst.concurrent;

/** Internal implementation interface of a Recipe. */
interface AsyncSupplier<T> {
  /** Returns the state that will eventually push a result to either receiver. */
  State eval(Receiver<T> onValue, Receiver<Throwable> onFailure);

  /** Like an async consumer, resulting state determines how we continue. */
  interface Receiver<T> {
    State receive(T t);
  }

  /**
   * A state of a recipe evaluation. We may either pull from a/another recipe, or push a result to a
   * receiver, or suspend the evaluation because of IO, in which case IO completion will restart it.
   */
  interface State {
    /**
     * Starts evaluating the given supplier and will eventually call onValue or onError, unless cancelled.
     */
    static <T> State pull(
        AsyncSupplier<T> supplier, Receiver<T> onValue, Receiver<Throwable> onError) {
      return new AutoValue_Computation_Pull<>(supplier, onValue, onError);
    }

    /**
     * Calls onValue and continues with its result.
     */
    static <T> State push(Receiver<T> onValue, T value) {
      return new AutoValue_Computation_Push<>(onValue, value);
    }

    /**
     * IO is a multi-way handshake. State startIO calls #start, which is allowed to fail or returns
     * a Cancellable. When started, it receives a Resumable which it must call when IO finishes or
     * is being cancelled.
     */
    interface IO {
      interface Resumable {
        void resumeWith(State state);
      }

      Executor.Cancellable start(Executor executor, Resumable whenDone) throws Throwable;
    }

    static State startIo(IO io, Receiver<Throwable> onError) {
      return new AutoValue_Computation_StartIo(io, onError);
    }
  }
}
