package org.mernst.grpc.server;

import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.multibindings.ProvidesIntoSet;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.mernst.concurrent.Executor;

import java.util.Set;

import static org.mernst.grpc.server.GrpcServiceModule.serviceBinder;

public class GrpcServerModule extends AbstractModule {
  public static LinkedBindingBuilder<BindableService> bindService(Binder binder) {
    return serviceBinder(binder).addBinding();
  }

  private final ServerBuilder<?> serverBuilder;

  public GrpcServerModule() {
    this(ServerBuilder.forPort(Integer.parseInt(System.getenv("PORT"))));
  }

  public GrpcServerModule(ServerBuilder<?> serverBuilder) {
    this.serverBuilder = serverBuilder;
  }

  @Override
  protected void configure() {
    serviceBinder(binder());
  }

  @ProvidesIntoSet
  Service serverStarter(Executor executor, Set<BindableService> services) {
    serverBuilder.executor(executor);
    services.forEach(serverBuilder::addService);
    Server server = serverBuilder.build();
    return new AbstractIdleService() {
      @Override
      protected void startUp() throws Exception {
        server.start();
      }

      @Override
      protected void shutDown() throws Exception {
        server.shutdown();
        server.awaitTermination();
      }
    };
  }
}
