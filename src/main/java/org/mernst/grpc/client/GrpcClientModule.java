package org.mernst.grpc.client;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.google.auto.value.AutoValue;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.name.Names;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.AbstractFutureStub;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.concurrent.Executor;
import java.util.function.Function;

public abstract class GrpcClientModule extends AbstractModule {
  private final String target;

  public GrpcClientModule(String target) {
    this.target = target;
  }

  @Override
  protected final void configure() {
    install(new AutoValue_ChannelModule(target));
    configureStubs();
  }

  protected abstract void configureStubs();

  protected final <T extends AbstractFutureStub<T>> void bindStub(
      Class<T> stubClass, Function<ManagedChannel, T> stubCreator) {
    Provider<ManagedChannel> channel = binder().getProvider(Key.get(ManagedChannel.class));
    bind(Key.get(stubClass))
        .toProvider(
            () -> {
              T apply = stubCreator.apply(channel.get());
              return configure(apply, target);
            });
  }

  protected final <T extends AbstractFutureStub<T>> void bindStub(
      Class<T> stubClass,
      Class<? extends Annotation> annotationClass,
      Function<ManagedChannel, T> stubCreator) {
    Provider<ManagedChannel> channel =
        binder().getProvider(Key.get(ManagedChannel.class, Names.named(target)));
    bind(Key.get(stubClass, annotationClass))
        .toProvider(
            () -> {
              T apply = stubCreator.apply(channel.get());
              return configure(apply, target);
            });
  }

  static <T extends AbstractFutureStub<T>> T configure(T stub, String target) {
    if (target.startsWith("localhost")) {
      return stub;
    }

    // https://cloud.google.com/run/docs/authenticating/service-to-service
    IdTokenCredentials idToken = null;
    try {
      idToken =
          IdTokenCredentials.newBuilder()
              .setIdTokenProvider((IdTokenProvider) GoogleCredentials.getApplicationDefault())
              .setTargetAudience("https://" + target)
              .build();
    } catch (IOException e) {
      throw new StatusRuntimeException(Status.INTERNAL.withCause(e));
    }
    return stub.withCallCredentials(MoreCallCredentials.from(idToken));
  }
}

@AutoValue
abstract class ChannelModule extends AbstractModule {
  abstract String target();

  @Override
  protected void configure() {
    Provider<Executor> executor = binder().getProvider(Executor.class);
    String target = target();

    bind(Key.get(ManagedChannel.class, Names.named(target)))
        .toProvider(
            () -> {
              ManagedChannelBuilder<?> channelBuilder =
                  ManagedChannelBuilder.forTarget(target).executor(executor.get());
              return target.startsWith("localhost")
                  ? channelBuilder.usePlaintext().build()
                  : channelBuilder.build();
            });
  }
}
