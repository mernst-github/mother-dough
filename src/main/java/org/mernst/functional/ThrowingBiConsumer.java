package org.mernst.functional;

public interface ThrowingBiConsumer<T, U> {
  void accept(T t, U u) throws Exception;
}
