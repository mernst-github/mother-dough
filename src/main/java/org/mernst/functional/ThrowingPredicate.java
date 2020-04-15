package org.mernst.functional;

public interface ThrowingPredicate<T> {
  boolean test(T value) throws Throwable;
}
