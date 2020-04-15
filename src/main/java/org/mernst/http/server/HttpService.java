package org.mernst.http.server;

import com.google.api.client.http.HttpResponseException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.inject.Provider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import org.mernst.concurrent.Executor;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;
import org.mernst.context.ScopeContext;
import org.mernst.http.server.HttpResult.ChunkedText;
import org.mernst.http.server.HttpResult.TextBody;
import org.mernst.http.server.HttpResult.TextChunk;

import java.io.PrintWriter;
import java.util.Map;

class HttpService extends AbstractIdleService {
  private final HttpServer server;
  private final Executor executor;
  private final ImmutableMap<String, Provider<Action>> actions;

  HttpService(HttpServer server, Executor executorService, Map<String, Provider<Action>> actions) {
    this.server = server;
    this.executor = executorService;
    this.actions = ImmutableMap.copyOf(actions);
  }

  @Override
  protected void startUp() {
    server.setExecutor(executor);
    actions.forEach(
        (path, action) ->
            server.createContext(
                path,
                exchange ->
                    ScopeContext.create()
                        .withValue(HttpServiceModule.HTTP_EXCHANGE_KEY, exchange)
                        .run(() -> execute(action))));
    server.start();
  }

  @Override
  protected void shutDown() {
    server.stop(0);
  }

  private void execute(Provider<Action> action) {
    Futures.addCallback(
        Recipe.from(action::get)
            .flatMap(Action::execute)
            .map(
                result ->
                    (result instanceof HttpResult
                        ? (HttpResult) result
                        : HttpResult.of(
                            HTTP_CODES.getOrDefault(
                                (result instanceof Status)
                                    ? ((Status) result).getCode()
                                    : Status.Code.OK,
                                500),
                            TextBody.plainUtf8(ChunkedText.of(result)))))
            .mapFailure(
                t ->
                    (HttpResult.of(
                        toHttpStatus(t),
                        TextBody.plainUtf8(
                            ChunkedText.of(
                                (TextChunk) w -> t.printStackTrace(new PrintWriter(w)))))))
            .consume(this::rendering)
            .start(executor),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {}

          @Override
          public void onFailure(Throwable t) {
            Thread thread = Thread.currentThread();
            thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
          }
        },
        Runnable::run);
  }

  private Integer toHttpStatus(Throwable t) {
    if (t instanceof HttpResponseException) {
      return ((HttpResponseException) t).getStatusCode();
    }
    Status.Code rpcStatusCode = Status.fromThrowable(t).getCode();
    return HTTP_CODES.getOrDefault(rpcStatusCode, 500);
  }

  private Plan rendering(HttpResult result) {
    HttpExchange exchange = HttpServiceModule.HTTP_EXCHANGE_KEY.get();
    Headers responseHeaders = exchange.getResponseHeaders();
    result.headers().stream().forEach(e -> responseHeaders.add(e.getKey(), e.getValue()));
    result
        .body()
        .map(HttpResult.Body::contentType)
        .ifPresent(t -> responseHeaders.set("content-type", t));

    return Plan.of(
            () -> exchange.sendResponseHeaders(result.status(), result.body().isPresent() ? 0 : -1))
        .then(
            () ->
                result
                    .body()
                    .map(body -> body.writingTo(exchange.getResponseBody()))
                    .orElse(Plan.none()))
        .exceptOn(Throwable.class, Throwable::printStackTrace)
        .then(exchange::close);
  }

  public static final ImmutableMap<Status.Code, Integer> HTTP_CODES =
      ImmutableMap.<Status.Code, Integer>builder()
          .put(Status.Code.OK, 200)
          .put(Status.Code.ALREADY_EXISTS, 412)
          .put(Status.Code.FAILED_PRECONDITION, 412)
          .put(Status.Code.ABORTED, 412)
          .put(Status.Code.NOT_FOUND, 404)
          .put(Status.Code.PERMISSION_DENIED, 403)
          .put(Status.Code.UNAUTHENTICATED, 401)
          .put(Status.Code.UNAVAILABLE, 503)
          .put(Status.Code.DEADLINE_EXCEEDED, 504)
          .put(Status.Code.INVALID_ARGUMENT, 400)
          .put(Status.Code.OUT_OF_RANGE, 400)
          .build();
}
