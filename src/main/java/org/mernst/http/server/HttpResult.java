package org.mernst.http.server;

import com.google.api.client.http.HttpResponseException;
import com.google.auto.value.AutoValue;
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
        Codes.toHttpStatus(rpcStatus),
        Streamable.of(),
        Body.plainUtf8(w -> Plan.of(() -> w.write(rpcStatus.toString()))));
  }

  public static HttpResult of(Throwable t) {
    return create(
        t instanceof HttpResponseException
            ? ((HttpResponseException) t).getStatusCode()
            : Codes.toHttpStatus(Status.fromThrowable(t)),
        Streamable.of(),
        Body.plainUtf8(w -> Plan.of(() -> t.printStackTrace(new PrintWriter(w)))));
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
      return of(Codes.TEXT_UTF8, StandardCharsets.UTF_8, text);
    }

    public static Body htmlUtf8(Text text) {
      return of(Codes.HTML_UTF8, StandardCharsets.UTF_8, text);
    }

    public static Body json(Text json) {
      return of(Codes.JSON, StandardCharsets.UTF_8, json);
    }
  }
}
