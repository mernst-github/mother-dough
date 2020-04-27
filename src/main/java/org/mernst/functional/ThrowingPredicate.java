package org.mernst.functional;

public interface ThrowingPredicate<T> {
  boolean test(T value) throws Throwable;

  default ThrowingPredicate<T> and(ThrowingPredicate<? super T> p) {
    return value -> test(value) && p.test(value);
  }

  default ThrowingPredicate<T> or(ThrowingPredicate<? super T> p) {
    return value -> test(value) || p.test(value);
  }
}
