package org.mernst.http.server;

import com.google.api.client.http.HttpResponseException;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.grpc.Status;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@AutoValue
public abstract class HttpResult {

  public static final String TEXT_UTF8 = "text/plain;charset=utf-8";
  public static final String HTML_UTF8 = "text/html;charset=utf-8";
  public static final String JSON = "application/json";

  public abstract int status();

  public abstract Streamable<Map.Entry<String, String>> headers();

  public abstract Optional<Body> body();

  public static HttpResult create(int status) {
    return create(status, Streamable.of());
  }

  public static HttpResult create(Body body) {
    return create(200, body);
  }

  public static HttpResult create(int status, Body body) {
    return create(status, Streamable.of(), body);
  }

  public static HttpResult create(int status, Streamable<Map.Entry<String, String>> headers) {
    return new AutoValue_HttpResult(status, headers, Optional.empty());
  }

  public static HttpResult create(
      int status, Streamable<Map.Entry<String, String>> headers, Body body) {
    return new AutoValue_HttpResult(status, headers, Optional.ofNullable(body));
  }

  public static HttpResult notModified() {
    return create(304);
  }

  public static HttpResult temporaryRedirectTo(String location) {
    return redirectTo(307, location);
  }

  public static HttpResult permanentRedirectTo(String location) {
    return redirectTo(308, location);
  }

  public static HttpResult redirectTo(int code, String location) {
    return create(code, Streamable.of(Maps.immutableEntry("Location", location)));
  }

  public static HttpResult of(int status) {
    return create(status);
  }

  public static HttpResult of(Status rpcStatus) {
    return create(
        toHttpStatus(rpcStatus),
        Streamable.of(),
        Body.plainUtf8(w -> Plan.of(() -> w.write(rpcStatus.toString()))));
  }

  public static HttpResult of(Throwable t) {
    return create(
        t instanceof HttpResponseException
            ? ((HttpResponseException) t).getStatusCode()
            : toHttpStatus(Status.fromThrowable(t)),
        Streamable.of(),
        Body.plainUtf8(w -> Plan.of(() -> t.printStackTrace(new PrintWriter(w)))));
  }

  private static Integer toHttpStatus(Status rpcStatus) {
    return HTTP_CODES.getOrDefault(rpcStatus.getCode(), 500);
  }

  public interface Binary {
    Plan writeTo(OutputStream o);
  }

  public interface Text {
    Plan writeTo(Writer w);

    default Binary encoded(Charset charset) {
      return out ->
          Recipe.from(() -> new OutputStreamWriter(out, charset))
              .consume(w -> writeTo(w).then(w::flush));
    }
  }

  @AutoValue
  public abstract static class Body {
    public abstract String contentType();

    public abstract Optional<String> eTag();

    public abstract Binary content();

    public static Body of(String contentType, String eTag, Binary body) {
      return new AutoValue_HttpResult_Body(contentType, Optional.of(eTag), body);
    }

    public static Body of(String contentType, Binary body) {
      return new AutoValue_HttpResult_Body(contentType, Optional.empty(), body);
    }

    public static Body of(String contentType, String eTag, Charset charset, Text body) {
      return of(contentType, eTag, body.encoded(charset));
    }

    public static Body of(String contentType, Charset charset, Text body) {
      return of(contentType, body.encoded(charset));
    }

    public static Body plainUtf8(Text text) {
      return of(TEXT_UTF8, StandardCharsets.UTF_8, text);
    }

    public static Body htmlUtf8(Text text) {
      return of(HTML_UTF8, StandardCharsets.UTF_8, text);
    }

    public static Body json(Text json) {
      return of(JSON, StandardCharsets.UTF_8, json);
    }
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
