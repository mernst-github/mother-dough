package org.mernst.functional;

public interface ThrowingSupplier<T> {
  T get() throws Throwable;
}
