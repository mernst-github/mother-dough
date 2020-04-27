package org.mernst.http.server;

import com.google.common.base.Strings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.mernst.concurrent.Recipe;

public interface Action {
  Recipe<HttpResult> execute() throws Exception;

  static void checkArgument(boolean ok, String errorMsg) {
    if (!ok) {
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(errorMsg));
    }
  }

  static void checkArgument(boolean ok, String formatString, Object... args) {
    if (!ok) {
      throw new StatusRuntimeException(
          Status.INVALID_ARGUMENT.withDescription(Strings.lenientFormat(formatString, args)));
    }
  }
}
