package org.mernst.server;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class ModuleBasedServer {
  public static void run(Module... modules) throws TimeoutException {
    run(ImmutableList.copyOf(modules));
  }

  public static void run(ImmutableList<Module> modules) throws TimeoutException {
    System.out.println(Runtime.version());
    System.getenv().forEach((k,v)->System.out.printf("%s=%s\n", k, v));
    ServiceManager serviceManager =
        Guice.createInjector(
                ImmutableList.<Module>builder()
                    .add(new ServiceManagerModule())
                    .addAll(modules)
                    .build())
            .getInstance(ServiceManager.class);
    serviceManager.startAsync();
    serviceManager.awaitHealthy(Duration.ofSeconds(30));
    serviceManager.awaitStopped();
  }

  private static class ServiceManagerModule extends AbstractModule {
    @Override
    protected void configure() {
      Multibinder.newSetBinder(binder(), Service.class);
    }

    @Provides
    ServiceManager serviceManager(Set<Service> services) {
      return new ServiceManager(services);
    }
  }
}
