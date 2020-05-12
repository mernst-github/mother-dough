package org.mernst.http.server;

import com.google.common.collect.ImmutableMap;
import io.grpc.Status;

public class Codes {
  public static final String TEXT_UTF8 = "text/plain;charset=utf-8";
  public static final String HTML_UTF8 = "text/html;charset=utf-8";
  public static final String JSON = "application/json";

  static Integer toHttpStatus(Status rpcStatus) {
    return HTTP_CODES.getOrDefault(rpcStatus.getCode(), 500);
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

  static ImmutableMap<String, String> CONTENT_TYPES =
      ImmutableMap.of(
          "html",
          HTML_UTF8,
          "css",
          "text/css;charset=utf-8",
          "js",
          "application/javascript",
          "svg",
          "image/svg+xml",
          "json",
          JSON);
}
