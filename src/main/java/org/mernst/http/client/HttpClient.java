package org.mernst.http.client;

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import okhttp3.*;
import org.mernst.concurrent.Recipe;
import org.mernst.functional.ThrowingSupplier;
import org.mernst.metrics.Metric;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

public class HttpClient {
  private final OkHttpClient ok;
  private final Metric apiRequestLatency;

  @Inject
  HttpClient(OkHttpClient ok, @Named("custom.googleapis.com/latency") Metric apiRequestLatency) {
    this.ok = ok;
    this.apiRequestLatency = apiRequestLatency;
  }

  private Recipe<Response> request(ThrowingSupplier<Request> request) {
    return Recipe.from(request)
        .flatMap(
            r ->
                Recipe.io(
                    (executor, whenDone) -> {
                      ok.newCall(r)
                          .enqueue(
                              new Callback() {
                                @Override
                                public void onFailure(Call call, IOException e) {
                                  whenDone.accept(Recipe.failed(e));
                                }

                                @Override
                                public void onResponse(Call call, Response response) {
                                  whenDone.accept(Recipe.to(response));
                                }
                              });
                      return () -> {};
                    }));
  }

  public Recipe<ResponseBody> get(String url) {
    return request(() -> new Request.Builder().url(url).build())
        .map(HttpClient::okBody)
        .flatMap(
            body ->
                Recipe.from(() -> ResponseBody.create(body.byteString(), body.contentType()))
                    .afterwards(body::close));
  }

  public <T> Recipe<T> get(ThrowingSupplier<? extends AbstractGoogleClientRequest<T>> r) {
    return Recipe.from(r)
        .flatMap(
            request -> {
              Instant start = Instant.now();
              HttpRequest httpRequest = request.buildHttpRequest();
              return request(
                      () ->
                          new Request.Builder()
                              .url(request.buildHttpRequestUrl().toString())
                              .method(
                                  httpRequest.getRequestMethod(), body(httpRequest.getContent()))
                              .headers(headers(httpRequest.getHeaders()))
                              .build())
                  .map(HttpClient::okBody)
                  .map(
                      body ->
                          httpRequest
                              .getParser()
                              .parseAndClose(
                                  body.byteStream(),
                                  body.contentType().charset(),
                                  request.getResponseClass()))
                  .afterwards(
                      success -> recordLatency(start, request, Status.Code.OK),
                      failure ->
                          recordLatency(start, request, Status.fromThrowable(failure).getCode()));
            });
  }

  static ResponseBody okBody(Response response) {
    if (response.code() >= 200 && response.code() < 300) {
      return response.body();
    } else if (response.code() == 401) {
      throw new StatusRuntimeException(Status.UNAUTHENTICATED);
    } else if (response.code() == 402) {
      throw new StatusRuntimeException(Status.PERMISSION_DENIED);
    } else if (response.code() == 403) {
      throw new StatusRuntimeException(Status.PERMISSION_DENIED);
    } else if (response.code() == 404) {
      throw new StatusRuntimeException(Status.NOT_FOUND);
    } else if (response.code() == 412) {
      throw new StatusRuntimeException(Status.FAILED_PRECONDITION);
    } else if (response.code() == 416) {
      throw new StatusRuntimeException(Status.OUT_OF_RANGE);
    } else if (response.code() >= 400 && response.code() < 500) {
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT);
    } else if (response.code() == 503) {
      throw new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
    } else {
      throw new StatusRuntimeException(Status.INTERNAL);
    }
  }

  public <T> void recordLatency(
      Instant start, AbstractGoogleClientRequest<T> request, Status.Code status) {
    apiRequestLatency.record(
        ImmutableMap.of(
            "request", request.getClass().getCanonicalName(), "status", status.toString()),
        Duration.between(start, Instant.now()).toNanos());
  }

  public Headers headers(HttpHeaders headers) {
    Headers.Builder result = new Headers.Builder();
    Stream.of("authorization")
        .map(k -> Maps.immutableEntry(k, headers.getHeaderStringValues(k)))
        .forEach(e -> e.getValue().forEach(v -> result.add(e.getKey(), v)));
    return result.build();
  }

  public RequestBody body(HttpContent content) throws IOException {
    if (content == null) return null;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    content.writeTo(baos);
    return RequestBody.create(baos.toByteArray(), MediaType.get(content.getType()));
  }
}
