package org.mernst.context;

import com.google.common.base.Preconditions;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scope;
import io.grpc.Context;

import java.util.HashMap;
import java.util.Map;

public class ScopeContext implements Scope {
  private static final Context.Key<Map<Key<?>, Object>> KEY =
      Context.key(ScopeContext.class.getName());

  public static Context create() {
    Context current = Context.current();
    Preconditions.checkState(KEY.get(current) == null, "Already in ContextScope");
    return current.withValue(KEY, new HashMap<>());
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Provider<T> scope(Key<T> key, Provider<T> unscoped) {
    return () ->
        (T)
            Preconditions.checkNotNull(KEY.get(), "Must have a org.mernst.context with scope cache")
                .computeIfAbsent(key, k -> unscoped.get());
  }
}
