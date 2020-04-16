package org.mernst.http.server;

import com.google.auto.value.AutoValue;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
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

  public static HttpResult create(int status, Streamable<Map.Entry<String, String>> headers) {
    return new AutoValue_HttpResult(status, headers, Optional.empty());
  }

  public static HttpResult create(
      int status, Streamable<Map.Entry<String, String>> headers, Body body) {
    return new AutoValue_HttpResult(status, headers, Optional.ofNullable(body));
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
}
