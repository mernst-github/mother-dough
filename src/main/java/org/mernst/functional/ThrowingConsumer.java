package org.mernst.functional;

public interface ThrowingConsumer<T> {
  void accept(T value) throws Exception;
}
