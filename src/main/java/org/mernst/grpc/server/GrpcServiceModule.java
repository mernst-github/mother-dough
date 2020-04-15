package org.mernst.grpc.server;

import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.multibindings.Multibinder;
import io.grpc.BindableService;

public class GrpcServiceModule extends AbstractModule {
  protected final LinkedBindingBuilder<BindableService> bindService() {
    return serviceBinder(binder()).addBinding();
  }

  static Multibinder<BindableService> serviceBinder(Binder binder) {
    return Multibinder.newSetBinder(binder, BindableService.class);
  }
}
