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
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Runs all instances bound to a {@link Service} set binder.
 */
public class Server {
  public static void run(Module... modules) throws TimeoutException {
    run(ImmutableList.copyOf(modules));
  }

  public static void run(ImmutableList<Module> modules) throws TimeoutException {
    System.out.printf(
        "Server starting at %s on JRE %s\n", Instant.now(), Runtime.version());
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
