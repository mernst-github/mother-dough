package org.mernst.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mernst.concurrent.Plan;
import org.mernst.functional.ThrowingBiConsumer;
import org.mernst.functional.ThrowingFunction;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.function.Function;

public class Jackson {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
          .disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);

  static final JsonFactory FACTORY = MAPPER.getFactory();

  public static <T> ThrowingBiConsumer<T, OutputStream> toBytes(Class<T> type) {
    ObjectWriter serializer = MAPPER.writerFor(type);
    return (instance, out) -> serializer.writeValue(out, instance);
  }

  public static <T> ThrowingBiConsumer<T, Writer> toChars(Class<T> type) {
    ObjectWriter serializer = MAPPER.writerFor(type);
    return (instance, w) -> serializer.writeValue(w, instance);
  }

  public static <T> Function<T, Json> toJson(Class<T> type) {
    ThrowingBiConsumer<T, Writer> serializer = toChars(type);
    return t -> w -> Plan.of(() -> serializer.accept(t, w));
  }

  public static <T> ThrowingFunction<InputStream, T> fromBytes(Class<T> type) {
    ObjectReader objectReader = MAPPER.readerFor(type);
    return r -> objectReader.readValue(FACTORY.createParser(r), type);
  }

  public static <T> ThrowingFunction<Reader, T> fromChars(Class<T> type) {
    ObjectReader objectReader = MAPPER.readerFor(type);
    return r -> objectReader.readValue(FACTORY.createParser(r), type);
  }
}
