package org.mernst.http.server;

import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Context;
import okhttp3.HttpUrl;
import org.mernst.concurrent.Executor;
import org.mernst.context.ContextScoped;

import java.util.Map;

public class HttpServiceModule extends AbstractModule {

  public static final Context.Key<HttpExchange> HTTP_EXCHANGE_KEY =
      Context.key(HttpExchange.class.getName());

  private final HttpServer server;

  public HttpServiceModule(HttpServer server) {
    this.server = server;
  }

  @Override
  protected void configure() {
    ActionsModule.actionBinder(binder());
  }

  @ProvidesIntoSet
  Service service(Executor executor, Map<String, Provider<Action>> actions) {
    return new HttpService(server, executor, actions);
  }

  @Provides
  static HttpExchange httpExchange() {
    return HTTP_EXCHANGE_KEY.get();
  }

  @Provides
  @ContextScoped
  static HttpUrl httpUrl(HttpExchange exchange) {
    return HttpUrl.parse("http://" + exchange.getRequestHeaders().get("Host").get(0))
        .resolve(exchange.getRequestURI().toString());
  }
}
