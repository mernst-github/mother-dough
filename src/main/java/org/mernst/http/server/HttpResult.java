package org.mernst.http.server;

import com.google.common.collect.Maps;
import okhttp3.HttpUrl;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface HttpResult {

  String TEXT_UTF8 = "text/plain;charset=utf-8";
  String HTML_UTF8 = "text/html;charset=utf-8";

  int status();

  Streamable<Map.Entry<String, String>> headers();

  Optional<Body> body();

  static HttpResult of(
      int status, Streamable<Map.Entry<String, String>> headers, Optional<Body> body) {
    return new HttpResult() {
      @Override
      public int status() {
        return status;
      }

      @Override
      public Streamable<Map.Entry<String, String>> headers() {
        return headers;
      }

      @Override
      public Optional<Body> body() {
        return body;
      }
    };
  }

  static HttpResult notModified() {
    return of(304, Streamable.of(), Optional.empty());
  }

  static HttpResult temporaryRedirect(HttpUrl location) {
    return redirect(307, location);
  }

  static HttpResult permanentRedirect(HttpUrl location) {
    return redirect(308, location);
  }

  static HttpResult redirect(int status, HttpUrl location) {
    return of(
        status,
        Streamable.of(Maps.immutableEntry("Location", location.toString())),
        Optional.empty());
  }

  static HttpResult of(int status) {
    return of(status, Streamable.of(), Optional.empty());
  }

  static HttpResult of(int status, Body body) {
    return of(status, Streamable.of(), Optional.of(body));
  }

  static HttpResult of(Body body) {
    return of(200, body);
  }

  static HttpResult plainUtf8(ChunkedText chunks) {
    return of(TextBody.plainUtf8(chunks));
  }

  static HttpResult plainUtf8(Object... values) {
    return plainUtf8(ChunkedText.of(values));
  }

  static HttpResult htmlUtf8(ChunkedText chunks) {
    return of(TextBody.htmlUtf8(chunks));
  }

  static HttpResult htmlUtf8(Object... values) {
    return htmlUtf8(ChunkedText.of(values));
  }

  interface Body {
    String contentType();

    Plan writingTo(OutputStream os);
  }

  interface TextBody extends Body {
    Charset charset();

    default Plan writingTo(OutputStream os) {
      OutputStreamWriter writer = new OutputStreamWriter(os, charset());
      return writingTo(writer).then(writer::flush);
    }

    Plan writingTo(Writer w);

    static TextBody of(String contentType, Charset charset, ChunkedText chunks) {
      return new TextBody() {
        @Override
        public Charset charset() {
          return charset;
        }

        @Override
        public String contentType() {
          return contentType;
        }

        @Override
        public Plan writingTo(Writer w) {
          return chunks.writingTo(w);
        }
      };
    }

    static TextBody plainUtf8(ChunkedText chunks) {
      return of(TEXT_UTF8, StandardCharsets.UTF_8, chunks);
    }

    static TextBody htmlUtf8(ChunkedText chunks) {
      return of(HTML_UTF8, StandardCharsets.UTF_8, chunks);
    }

    static TextBody json(ChunkedText chunks) {
      return of("application/json", StandardCharsets.UTF_8, chunks);
    }
  }

  interface ChunkedText {
    Plan writingTo(Writer w);

    static <T> ChunkedText of(Streamable<T> values) {
      return w -> writingTo(w, values.stream().iterator());
    }

    static <T> ChunkedText of(Object... values) {
      return of(Streamable.of(values));
    }

    static <T> ChunkedText flushing(Streamable<T> values) {
      return w -> of(values).writingTo(w).then(w::flush);
    }

    static <T> ChunkedText flushing(Object... values) {
      return flushing(Streamable.of(values));
    }

    static <T> Plan writingTo(Writer w, Iterator<T> it) {
      if (!it.hasNext()) {
        return Plan.none();
      }
      return writingTo(w, it.next()).then(() -> writingTo(w, it));
    }

    static Plan writingTo(Writer w, Object o) {
      if (o instanceof Recipe) {
        // "deref"
        return ((Recipe<?>) o).consume(v -> writingTo(w, v));
      }
      if (o instanceof ChunkedText) {
        return ((ChunkedText) o).writingTo(w);
      }
      return Plan.of(() -> writeTo(w, o));
    }

    static void writeTo(Writer w, Object o) throws IOException {
      if (o instanceof TextChunk) {
        ((TextChunk) o).writeTo(w);
      } else {
        w.write(Objects.toString(o));
      }
    }
  }

  interface TextChunk {
    void writeTo(Writer writer) throws IOException;

    static TextChunk flush() {
      return Writer::flush;
    }
  }
}
