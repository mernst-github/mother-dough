package org.mernst.http.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.mernst.concurrent.Recipe;

public interface Action {
  Recipe<HttpResult> execute();

  static void checkArgument(boolean ok, String errorMsg) {
    if (!ok) {
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(errorMsg));
    }
  }
}
