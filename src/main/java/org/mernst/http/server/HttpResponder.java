package org.mernst.http.server;

import com.google.api.client.http.HttpResponseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.grpc.Status;
import okhttp3.HttpUrl;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;

import javax.inject.Inject;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;

public class HttpResponder {
  private final HttpUrl base;
  private final Map<String, List<String>> requestHeaders;

  @Inject
  HttpResponder(HttpUrl base, @Headers Map<String, List<String>> requestHeaders) {
    this.base = base;
    this.requestHeaders = requestHeaders;
  }

  public HttpResult of(
      int status, Streamable<Map.Entry<String, String>> headers, HttpResult.Body body) {
    Streamable<Map.Entry<String, String>> withContentType =
        () ->
            Stream.concat(
                headers.stream(),
                Stream.of(Maps.immutableEntry("Content-Type", body.contentType())));
    return body.eTag()
        .map(
            tag ->
                requestHeaders.getOrDefault("If-None-Match", ImmutableList.of()).contains(tag)
                    ? notModified()
                    : HttpResult.create(
                        status,
                        () ->
                            Stream.concat(
                                withContentType.stream(),
                                Stream.of(Maps.immutableEntry("ETag", tag))),
                        body))
        .orElseGet(() -> HttpResult.create(status, withContentType, body));
  }

  public HttpResult notModified() {
    return HttpResult.create(304, Streamable.of());
  }

  interface Redirector {
    HttpResult to(String location);
    HttpResult to(HttpUrl location);
  }

  public Redirector temporaryRedirect() {
    return redirect(307);
  }

  public Redirector permanentRedirect() {
    return redirect(308);
  }

  public Redirector redirect(int status) {
    return new Redirector() {
      @Override
      public HttpResult to(String location) {
        return to(checkNotNull(base.resolve(location), "%s against %s", location, base));
      }

      @Override
      public HttpResult to(HttpUrl location) {
        return HttpResult.create(
            status, Streamable.of(Maps.immutableEntry("Location", location.toString())));
      }
    };
  }

  public HttpResult of(int status) {
    return HttpResult.create(status, Streamable.of());
  }

  public HttpResult of(Status rpcStatus) {
    return of(
        toHttpStatus(rpcStatus),
        HttpResult.Body.plainUtf8(w -> Plan.of(() -> w.write(rpcStatus.toString()))));
  }

  public HttpResult of(Throwable t) {
    return of(
        t instanceof HttpResponseException
            ? ((HttpResponseException) t).getStatusCode()
            : toHttpStatus(Status.fromThrowable(t)),
        HttpResult.Body.plainUtf8(w -> Plan.of(() -> t.printStackTrace(new PrintWriter(w)))));
  }

  private static Integer toHttpStatus(Status rpcStatus) {
    return HTTP_CODES.getOrDefault(rpcStatus.getCode(), 500);
  }

  public HttpResult of(int status, HttpResult.Body body) {
    return of(status, Streamable.of(), body);
  }

  public HttpResult of(HttpResult.Body body) {
    return of(200, body);
  }

  private static final ImmutableMap<Status.Code, Integer> HTTP_CODES =
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
