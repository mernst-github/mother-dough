package org.mernst.grpc.client;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
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

public class GrpcClientModule extends AbstractModule {
  private final String target;

  public GrpcClientModule(String target) {
    this.target = target;
  }

  protected final void bindChannel() {
    Provider<Executor> executor = binder().getProvider(Executor.class);
    bind(Key.get(ManagedChannel.class, Names.named(target)))
        .toProvider(() -> channelFor(target, executor.get()));
  }

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

  static ManagedChannel channelFor(String target, Executor executor) {
    ManagedChannelBuilder<?> channelBuilder =
        ManagedChannelBuilder.forTarget(target).executor(executor);
    if (!target.startsWith("localhost")) {
      return channelBuilder.build();
    }

    // local: plaintext, no creds
    return channelBuilder.usePlaintext().build();
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
