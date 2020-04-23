package org.mernst.http.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Provider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;
import org.mernst.context.ScopeContext;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

class HttpService extends AbstractIdleService {
  private final HttpServer server;
  private final ScheduledExecutorService scheduler;
  private final ImmutableMap<String, Provider<Action>> actions;

  @Inject
  HttpService(
      HttpServer server,
      ScheduledExecutorService scheduler,
      Map<String, Provider<Action>> actions) {
    this.server = server;
    this.scheduler = scheduler;
    this.actions = ImmutableMap.copyOf(actions);
  }

  @Override
  protected void startUp() {
    server.setExecutor(scheduler);
    actions.forEach(
        (path, boundAction) -> {
          int noSlash = path.length();
          while (noSlash > 0 && path.charAt(noSlash - 1) == '/') {
            --noSlash;
          }
          boolean directory = noSlash < path.length();
          String basePath = path.substring(0, noSlash);

          server.createContext(
              basePath,
              exchange ->
                  ScopeContext.create()
                      .withValue(HttpServiceModule.HTTP_EXCHANGE_KEY, exchange)
                      .run(
                          () ->
                              execute(
                                  matches(exchange, basePath, directory)
                                      ? boundAction.get()
                                      : () -> Recipe.to(HttpResult.create(404)))));
        });
    server.start();
  }

  private static boolean matches(HttpExchange exchange, String basePath, boolean directory) {
    // Prevent arbitrary postfix matches allowed by sun httpserver
    // (for example "index.htmlfoo").
    String requestPath = exchange.getRequestURI().getPath();
    Preconditions.checkArgument(requestPath.startsWith(basePath));
    String postfix = requestPath.substring(basePath.length());
    return postfix.isEmpty() || (directory && postfix.startsWith("/"));
  }

  @Override
  protected void shutDown() {
    server.stop(0);
  }

  private void execute(Action action) {
    Recipe.to(action)
        .flatMap(Action::execute)
        .mapFailure(HttpResult::of)
        .consume(this::render)
        .afterwards(
            () -> {},
            t -> {
              Thread thread = Thread.currentThread();
              thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
            })
        .start(scheduler);
  }

  private Plan render(HttpResult result) {
    HttpExchange exchange = HttpServiceModule.HTTP_EXCHANGE_KEY.get();
    Headers responseHeaders = exchange.getResponseHeaders();
    result.headers().stream().forEach(e -> responseHeaders.add(e.getKey(), e.getValue()));
    return Plan.of(
            () -> exchange.sendResponseHeaders(result.status(), result.body().isPresent() ? 0 : -1))
        .then(
            () ->
                result
                    .body()
                    .map(body -> body.content().writeTo(exchange.getResponseBody()))
                    .orElse(Plan.none()))
        .exceptOn(Throwable.class, Throwable::printStackTrace)
        .then(exchange::close);
  }
}
