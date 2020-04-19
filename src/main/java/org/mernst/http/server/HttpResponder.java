package org.mernst.http.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.mernst.collect.Streamable;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class HttpResponder {
  private final Map<String, List<String>> requestHeaders;

  @Inject
  HttpResponder(@Headers Map<String, List<String>> requestHeaders) {
    this.requestHeaders = requestHeaders;
  }

  public HttpResult ifUnmodified(
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
                    ? HttpResult.notModified()
                    : HttpResult.create(
                        status,
                        () ->
                            Stream.concat(
                                withContentType.stream(),
                                Stream.of(Maps.immutableEntry("ETag", tag))),
                        body))
        .orElseGet(() -> HttpResult.create(status, withContentType, body));
  }

  public HttpResult ifUnmodified(int status, HttpResult.Body body) {
    return ifUnmodified(status, Streamable.of(), body);
  }

  public HttpResult ifUnmodified(HttpResult.Body body) {
    return ifUnmodified(200, body);
  }
}
