package org.mernst.functional;

public interface ThrowingFunction<T, U> {
U apply(T value) throws Throwable;
}
