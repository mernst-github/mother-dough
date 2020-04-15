package org.mernst.functional;

public interface ThrowingBiFunction<T, U, V> {
V apply(T t, U u) throws Throwable;
}
