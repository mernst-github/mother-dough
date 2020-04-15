package org.mernst.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Maps;
import org.mernst.collect.Streamable;
import org.mernst.concurrent.Plan;
import org.mernst.concurrent.Recipe;
import org.mernst.http.server.HttpResult;

import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public interface Json extends HttpResult.ChunkedText {
  static <T> Function<T, Json> serializerFor(Class<T> type) {
    ObjectWriter serializer = Jackson.MAPPER.writerFor(type);
    return instance ->
        w ->
            Plan.none()
                .then(
                    () -> {
                      serializer.writeValue(w, instance);
                    });
  }

  static <T> Function<InputStream, Recipe<T>> fromBytes(Class<T> type) {
    ObjectReader objectReader = Jackson.MAPPER.readerFor(type);
    return r ->
        Recipe.to(null)
            .map(ignore -> objectReader.readValue(Jackson.FACTORY.createParser(r), type));
  }

  static <T> Function<Reader, Recipe<T>> fromChars(Class<T> type) {
    ObjectReader objectReader = Jackson.MAPPER.readerFor(type);
    return r ->
        Recipe.to(null)
            .map(ignore -> objectReader.readValue(Jackson.FACTORY.createParser(r), type));
  }

  static Json number(long n) {
    return w -> write(String.valueOf(n), w);
  }

  static Json string(String s) {
    return w ->
        Plan.none()
            .then(
                () -> {
                  w.write("\"");
                  int off = 0;
                  while (off < s.length()) {
                    {
                      int sep = off;
                      char sepChar;
                      while (sep < s.length()
                          && ((sepChar = s.charAt(sep)) != '"'
                              && sepChar != '\\'
                              && sepChar >= 20)) {
                        ++sep;
                      }
                      w.write(s, off, sep - off);
                      off = sep;
                    }
                    if (off < s.length()) {
                      char sepChar = s.charAt(off);
                      switch (sepChar) {
                        case '"':
                          w.write("\\\"");
                          break;
                        case '\\':
                          w.write("\\\\");
                          break;
                        case '\n':
                          w.write("\\n");
                          break;
                        case '\t':
                          w.write("\\t");
                          break;
                        default:
                          w.write("\\u");
                          w.write(String.format("%04x", (int) sepChar));
                          break;
                      }
                      ++off;
                    }
                  }
                  w.write("\"");
                  return Plan.none();
                });
  }

  static Json object(Map<String, Json> attributes) {
    return object(() -> attributes.entrySet().stream());
  }

  static Json object(Streamable<Map.Entry<String, Json>> attributes) {
    return w ->
        write("{", w)
            .then(
                () ->
                    writeDelimited(
                        attributes.stream()
                            .<Json>map(
                                e ->
                                    w2 ->
                                        string(e.getKey())
                                            .writingTo(w2)
                                            .then(() -> write(": ", w2))
                                            .then(() -> e.getValue().writingTo(w2)))
                            .iterator(),
                        ",\n",
                        w))
            .then(() -> write("}", w));
  }

  static Json array(Streamable<Json> values) {
    return w ->
        write("[", w)
            .then(() -> writeDelimited(values.stream().iterator(), ", ", w))
            .then(() -> write("]", w));
  }

  static Json array(Json... values) {
    return array(Streamable.of(values));
  }

  static Json object(Map.Entry<String, Json>... attributes) {
    return object(Streamable.of(attributes));
  }

  static Json object(String name1, Json value1) {
    return object(() -> Stream.of(attribute(name1, value1)));
  }

  static Json object(String name1, Json value1, String name2, Json value2) {
    return object(() -> Stream.of(attribute(name1, value1), attribute(name2, value2)));
  }

  static Json object(
      String name1, Json value1, String name2, Json value2, String name3, Json value3) {
    return object(
        () ->
            Stream.of(
                attribute(name1, value1), attribute(name2, value2), attribute(name3, value3)));
  }

  static Map.Entry<String, Json> attribute(String name, Json value) {
    return Maps.immutableEntry(name, value);
  }

  static Plan write(String string, Writer w) {
    return Plan.none().then(() -> w.write(string));
  }

  static Plan writeDelimited(Iterator<Json> json, String delimiter, Writer w) {
    return !json.hasNext()
        ? Plan.none()
        : json.next().writingTo(w).then(() -> writePrefixed(delimiter, json, w));
  }

  static Plan writePrefixed(String delimiter, Iterator<Json> json, Writer w) {
    return !json.hasNext()
        ? Plan.none()
        : write(delimiter, w)
            .then(() -> json.next().writingTo(w))
            .then(() -> writePrefixed(delimiter, json, w));
  }
}

class Jackson {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
          .disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);

  static final JsonFactory FACTORY = MAPPER.getFactory();
}
