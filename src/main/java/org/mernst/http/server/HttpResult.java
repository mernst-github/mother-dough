package org.mernst.http.server;

import com.google.api.client.http.HttpResponseException;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.grpc.Status;
import okhttp3.HttpUrl;
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

  public static HttpResult of(
      int status, Streamable<Map.Entry<String, String>> headers, Optional<Body> body) {
    return new AutoValue_HttpResult(status, headers, body);
  }

  public static HttpResult notModified() {
    return of(304, Streamable.of(), Optional.empty());
  }

  public static HttpResult temporaryRedirect(HttpUrl location) {
    return redirect(307, location);
  }

  public static HttpResult permanentRedirect(HttpUrl location) {
    return redirect(308, location);
  }

  public static HttpResult redirect(int status, HttpUrl location) {
    return of(
        status,
        Streamable.of(Maps.immutableEntry("Location", location.toString())),
        Optional.empty());
  }

  public static HttpResult of(int status) {
    return of(status, Streamable.of(), Optional.empty());
  }

  public static HttpResult of(Status rpcStatus) {
    return of(
        toHttpStatus(rpcStatus), Body.plainUtf8(w -> Plan.of(() -> w.write(rpcStatus.toString()))));
  }

  public static HttpResult of(Throwable t) {
    return of(
        t instanceof HttpResponseException
            ? ((HttpResponseException) t).getStatusCode()
            : toHttpStatus(Status.fromThrowable(t)),
        Body.plainUtf8(w -> Plan.of(() -> t.printStackTrace(new PrintWriter(w)))));
  }

  private static Integer toHttpStatus(Status rpcStatus) {
    return HTTP_CODES.getOrDefault(rpcStatus.getCode(), 500);
  }

  public static HttpResult of(int status, Body body) {
    return of(status, Streamable.of(), Optional.of(body));
  }

  public static HttpResult of(Body body) {
    return of(200, body);
  }

  public interface Text {
    Plan writeTo(Writer w);

    default Binary encoded(Charset charset) {
      return out ->
          Recipe.from(() -> new OutputStreamWriter(out, charset))
              .consume(w -> writeTo(w).then(w::flush));
    }
  }

  public interface Binary {
    Plan writeTo(OutputStream o);
  }

  @AutoValue
  public abstract static class Body {
    public abstract String contentType();

    public abstract Binary content();

    public static Body of(String contentType, Binary body) {
      return new AutoValue_HttpResult_Body(contentType, body);
    }

    public static Body of(String contentType, Charset charset, Text body) {
      return Body.of(contentType, body.encoded(charset));
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
