package org.mernst.context;

import com.google.inject.AbstractModule;

public class ContextModule extends AbstractModule {

  public static ContextModule create() {
    return INSTANCE;
  }

  @Override
  protected void configure() {
    bindScope(ContextScoped.class, new ScopeContext());
  }

  private ContextModule() {}

  private static final ContextModule INSTANCE = new ContextModule();
}
