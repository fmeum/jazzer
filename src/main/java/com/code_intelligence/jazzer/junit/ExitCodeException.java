/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * By downloading, you agree to the Code Intelligence Jazzer Terms and Conditions.
 *
 * The Code Intelligence Jazzer Terms and Conditions are provided in LICENSE-JAZZER.txt
 * located in the root directory of the project.
 */

package com.code_intelligence.jazzer.junit;

public final class ExitCodeException extends Exception {
  public final int exitCode;

  public ExitCodeException(String message, int exitCode) {
    super(message);
    this.exitCode = exitCode;
  }
}
