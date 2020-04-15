package org.mernst.collect;

import com.google.common.collect.Streams;

import java.util.Arrays;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * A more modern replacement for Iterable, esp lets you pass transformed streams directory without
 * .collect(toImmutableList()) or ()->....iterator().
 */
public interface Streamable<T> {
  Stream<T> stream();

  static <T> Streamable<T> of(Iterable<T> items) {
    return () -> Streams.stream(items);
  }

  @SafeVarargs
  static <T> Streamable<T> of(T... items) {
    return () -> Arrays.stream(items);
  }

  /**
   * Collects this streamable into a list for repeated streaming or early error detection.
   */
  default Streamable<T> materialized() {
    return stream().collect(toImmutableList())::stream;
  }
}
