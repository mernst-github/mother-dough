package org.mernst.http.server;

import com.google.auto.value.AutoAnnotation;
import com.google.common.base.Converter;
import com.google.common.base.Enums;
import com.google.common.collect.MoreCollectors;
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
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Recipe;

import javax.inject.Provider;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class ActionModule extends BaseModule {
  private final String basePath;

  public ActionModule(String basePath) {
    this.basePath = basePath;
  }

  private String resolve(String relativePath) {
    return basePath.length() == 1
        ? relativePath
        : relativePath.length() == 1 ? basePath : basePath + relativePath;
  }

  protected LinkedBindingBuilder<Action> bindAction(String relativePath) {
    return actionBinder(binder()).addBinding(resolve(relativePath));
  }

  protected void redirectPermanently(String path, String destination) {
    bindAction(path)
        .toInstance(() -> Recipe.from(() -> HttpResult.permanentRedirectTo(resolve(destination))));
  }

  protected void redirectTemporarily(String path, String destination) {
    bindAction(path)
        .toInstance(() -> Recipe.from(() -> HttpResult.temporaryRedirectTo(resolve(destination))));
  }

  protected void defaultDocument(String dir, String documentPath) {
    redirectTemporarily(dir, dir + documentPath);
  }

  @Override
  protected final void configure() {
    install(COMMON_BINDINGS);
    configureActions();
  }

  protected abstract void configureActions();
}

class BaseModule extends AbstractModule {
  private Streamable<String> lazyParams(String name) {
    Provider<HttpUrl> url = getProvider(HttpUrl.class);
    return () -> url.get().queryParameterValues(name).stream();
  }

  private <T> Provider<Streamable<T>> parameters(
      String name, Provider<Converter<String, T>> converterProvider) {
    Streamable<String> parameters = lazyParams(name);
    return () -> {
      Converter<String, T> converter = converterProvider.get();
      return () ->
          parameters.stream()
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
                  });
    };
  }

  private <T> Provider<Optional<T>> optionalParameter(String name, Class<T> type) {
    Provider<Streamable<T>> parameters = parameters(name, getProvider(converterTo(type)));
    return () -> parameters.get().stream().collect(MoreCollectors.toOptional());
  }

  private <T> Provider<T> parameter(String name, Class<T> type, T defaultValue) {
    Provider<Optional<T>> optionalProvider = optionalParameter(name, type);
    return () -> optionalProvider.get().orElse(defaultValue);
  }

  @SuppressWarnings("unchecked")
  protected <T> Provider<Streamable<T>> bindParameters(String name, Class<T> type) {
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
  protected <T> Provider<Streamable<T>> bindParameters(
      String name, Class<T> type, String annotationName) {
    return bindParameter(
        annotationName,
        (TypeLiteral<Streamable<T>>)
            TypeLiteral.get(Types.newParameterizedType(Streamable.class, type)),
        parameters(name, getProvider(converterTo(type))));
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
