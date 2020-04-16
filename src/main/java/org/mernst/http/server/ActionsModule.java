package org.mernst.http.server;

import com.google.auto.value.AutoAnnotation;
import com.google.common.base.Converter;
import com.google.common.base.Enums;
import com.google.common.collect.Streams;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Floats;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.util.Types;
import com.sun.net.httpserver.HttpExchange;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import okhttp3.HttpUrl;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import javax.inject.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class ActionsModule extends BaseModule {

  protected LinkedBindingBuilder<Action> bindAction(String path) {
    return actionBinder(binder()).addBinding(path);
  }

  protected void redirectPermanently(String path, String destination) {
    bindAction(path)
        .toInstance(responder -> Recipe.from(() -> responder.permanentRedirect().to(destination)));
  }

  protected void redirectTemporarily(String path, String destination) {
    bindAction(path)
        .toInstance(responder -> Recipe.from(() -> responder.temporaryRedirect().to(destination)));
  }

  protected void defaultDocument(String dir, String documentPath) {
    redirectTemporarily(dir, dir + documentPath);
  }

  protected void bindResource(
      String basePath, String resourceDirectory, String relativePath, String contentType) {
    bindResource(basePath, getClass(), resourceDirectory, relativePath, contentType);
  }

  protected void bindResource(
      String basePath,
      Class<?> baseClass,
      String resourceDirectory,
      String relativePath,
      String contentType) {
    bindAction(basePath + relativePath)
        .toInstance(new ResourceAction(baseClass, resourceDirectory, relativePath, contentType));
  }

  @Override
  protected final void configure() {
    install(COMMON_BINDINGS);
    configureActions();
  }

  protected abstract void configureActions();

  static class ResourceAction implements Action {
    private final Class<?> baseClass;
    private final String resourceDirectory;
    private final String relativePath;
    private final String tag;
    private final String contentType;

    ResourceAction(
        Class<?> baseClass, String resourceDirectory, String relativePath, String contentType) {
      this.contentType = contentType;
      this.baseClass = baseClass;
      this.resourceDirectory = resourceDirectory;
      this.relativePath = relativePath;

      try {
        Hasher hashFunction = Hashing.murmur3_128(0).newHasher();
        ByteStreams.copy(
            baseClass.getResourceAsStream(resourceDirectory.substring(1) + relativePath),
            new OutputStream() {
              @Override
              public void write(byte[] b, int off, int len) {
                hashFunction.putBytes(b, off, len);
              }

              @Override
              public void write(int i) {
                hashFunction.putByte((byte) i);
              }
            });
        tag = String.format("\"%s\"", hashFunction.hash());
      } catch (IOException io) {
        throw new IllegalArgumentException(
            baseClass.getName() + resourceDirectory + relativePath, io);
      }
    }

    @Override
    public Recipe<HttpResult> execute(HttpResponder responder) {
      return Recipe.to(
          responder.of(
              200,
              HttpResult.Body.of(
                  contentType,
                  tag,
                  os ->
                      Plan.of(
                          () ->
                              ByteStreams.copy(
                                  baseClass.getResourceAsStream(
                                      resourceDirectory.substring(1) + relativePath),
                                  os)))));
    }
  }
}

class BaseModule extends AbstractModule {
  private Iterable<String> lazyParams(String name) {
    Provider<HttpUrl> url = getProvider(HttpUrl.class);
    return () -> url.get().queryParameterValues(name).iterator();
  }

  private <T> Provider<Iterable<T>> parameters(String name, Class<T> type) {
    Iterable<String> parameters = lazyParams(name);
    Provider<Converter<String, T>> converterProvider = getProvider(converterTo(type));
    return () -> {
      Converter<String, T> converter = converterProvider.get();
      return () ->
          Streams.stream(parameters)
              .map(
                  s -> {
                    try {
                      return converter.convert(s);
                    } catch (IllegalArgumentException iae) {
                      throw new StatusRuntimeException(
                          Status.INVALID_ARGUMENT
                              .withDescription(name + ": " + iae.getMessage())
                              .withCause(iae));
                    }
                  })
              .iterator();
    };
  }

  private <T> Provider<Optional<T>> optionalParameter(String name, Class<T> type) {
    Provider<Iterable<T>> parameters = parameters(name, type);
    return () -> Streams.stream(parameters.get()).findFirst();
  }

  private <T> Provider<T> parameter(String name, Class<T> type, T defaultValue) {
    Provider<Optional<T>> optionalProvider = optionalParameter(name, type);
    return () -> optionalProvider.get().orElse(defaultValue);
  }

  @SuppressWarnings("unchecked")
  protected <T> Provider<Iterable<T>> bindParameters(String name, Class<T> type) {
    return bindParameters(name, type, name);
  }

  @SuppressWarnings("unchecked")
  protected <T> Provider<Optional<T>> bindOptionalParameter(String name, Class<T> type) {
    return bindOptionalParameter(name, type, name);
  }

  protected <T> Provider<T> bindParameter(String name, Class<T> type, T defaultValue) {
    return bindParameter(name, type, defaultValue, name);
  }

  @SuppressWarnings("unchecked")
  protected <T> Provider<Iterable<T>> bindParameters(
      String name, Class<T> type, String annotationName) {
    return bindParameter(
        annotationName,
        (TypeLiteral<Iterable<T>>)
            TypeLiteral.get(Types.newParameterizedType(Iterable.class, type)),
        parameters(name, type));
  }

  @SuppressWarnings("unchecked")
  protected <T> Provider<Optional<T>> bindOptionalParameter(
      String name, Class<T> type, String annotationName) {
    return bindParameter(
        annotationName,
        (TypeLiteral<Optional<T>>)
            TypeLiteral.get(Types.newParameterizedType(Optional.class, type)),
        optionalParameter(name, type));
  }

  protected <T> Provider<T> bindParameter(
      String name, Class<T> type, T defaultValue, String annotationName) {
    return bindParameter(
        annotationName, TypeLiteral.get(type), parameter(name, type, defaultValue));
  }

  private <T> Provider<T> bindParameter(String name, TypeLiteral<T> type, Provider<T> result) {
    bind(Key.get(type, paramAnnotation(name))).toProvider(result);
    return result;
  }

  protected <T> LinkedBindingBuilder<Converter<String, T>> bindConverter(Class<T> type) {
    return binder().bind(converterTo(type));
  }

  protected <T extends Enum<T>> void bindEnumConverter(Class<T> enumClass) {
    binder().bind(converterTo(enumClass)).toInstance(Enums.stringConverter(enumClass));
  }

  @SuppressWarnings("unchecked")
  private <T> Key<Converter<String, T>> converterTo(Class<T> type) {
    return Key.get(
        (TypeLiteral<Converter<String, T>>)
            TypeLiteral.get(Types.newParameterizedType(Converter.class, String.class, type)));
  }

  @AutoAnnotation
  private static Param paramAnnotation(String value) {
    return new AutoAnnotation_BaseModule_paramAnnotation(value);
  }

  static MapBinder<String, Action> actionBinder(Binder binder) {
    return MapBinder.newMapBinder(binder, String.class, Action.class);
  }

  static final BaseModule COMMON_BINDINGS =
      new BaseModule() {
        @Override
        protected void configure() {
          bindConverter(String.class).toInstance(Converter.identity());
          bindConverter(Integer.class).toInstance(Ints.stringConverter());
          bindConverter(Long.class).toInstance(Longs.stringConverter());
          bindConverter(Float.class).toInstance(Floats.stringConverter());
          bindConverter(Double.class).toInstance(Doubles.stringConverter());

          Provider<HttpExchange> httpExchangeProvider = getProvider(HttpExchange.class);
          bind(String.class)
              .annotatedWith(RequestMethod.class)
              .toProvider(() -> httpExchangeProvider.get().getRequestMethod());
          bind(new TypeLiteral<Map<String, List<String>>>() {})
              .annotatedWith(Headers.class)
              .toProvider(() -> httpExchangeProvider.get().getRequestHeaders());
          bind(InputStream.class).toProvider(() -> httpExchangeProvider.get().getRequestBody());
          Provider<HttpUrl> urlProvider = getProvider(HttpUrl.class);
          bind(String.class)
              .annotatedWith(Path.class)
              .toProvider(() -> urlProvider.get().encodedPath());
        }
      };
}
