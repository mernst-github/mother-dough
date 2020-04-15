package org.mernst.functional;

public class Failure {
  public static <T> T throwing(RuntimeException t) {
    throw t;
  }
}
