/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * By downloading, you agree to the Code Intelligence Jazzer Terms and Conditions.
 *
 * The Code Intelligence Jazzer Terms and Conditions are provided in LICENSE-JAZZER.txt
 * located in the root directory of the project.
 */

package com.code_intelligence.jazzer.driver.junit;

import static com.code_intelligence.jazzer.driver.Constants.JAZZER_FINDING_EXIT_CODE;
import static org.junit.platform.engine.FilterResult.includedIf;
import static org.junit.platform.engine.TestExecutionResult.Status.ABORTED;
import static org.junit.platform.engine.TestExecutionResult.Status.FAILED;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.TagFilter.includeTags;

import com.code_intelligence.jazzer.driver.ExceptionUtils;
import com.code_intelligence.jazzer.driver.Opt;
import com.code_intelligence.jazzer.utils.Log;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.jupiter.engine.descriptor.MethodBasedTestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

public final class JUnitRunner {
  private final Launcher launcher;
  private final TestPlan testPlan;

  private JUnitRunner(Launcher launcher, TestPlan testPlan) {
    this.launcher = launcher;
    this.testPlan = testPlan;
  }

  // Detects the presence of both the JUnit launcher and the Jupiter engine on the classpath.
  public static boolean isSupported() {
    try {
      Class.forName("org.junit.platform.launcher.LauncherDiscoveryRequest");
      Class.forName("org.junit.jupiter.engine.JupiterTestEngine");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  public static Optional<JUnitRunner> create(String testClassName, List<String> libFuzzerArgs) {
    // We want the test execution to be as lightweight as possible, so disable all auto-discover and
    // only register the test engine we are using for @FuzzTest, JUnit Jupiter.
    LauncherConfig config =
        LauncherConfig.builder()
            .addTestEngines(new JupiterTestEngine())
            .enableLauncherDiscoveryListenerAutoRegistration(false)
            .enableLauncherSessionListenerAutoRegistration(false)
            .enablePostDiscoveryFilterAutoRegistration(false)
            .enableTestEngineAutoRegistration(false)
            .enableTestExecutionListenerAutoRegistration(false)
            .build();

    Map<String, String> indexedArgs =
        IntStream.range(0, libFuzzerArgs.size())
            .boxed()
            .collect(Collectors.toMap(i -> "jazzer.internal.arg." + i, libFuzzerArgs::get));

    LauncherDiscoveryRequestBuilder requestBuilder =
        LauncherDiscoveryRequestBuilder.request()
            // JUnit's timeout handling interferes with libFuzzer as from the point of view of JUnit
            // all fuzz test invocations are combined in a single JUnit test method execution.
            // https://junit.org/junit5/docs/current/user-guide/#writing-tests-declarative-timeouts-mode
            .configurationParameter("junit.jupiter.execution.timeout.mode", "disabled")
            .configurationParameter("jazzer.internal.command_line", "true")
            .configurationParameters(indexedArgs)
            .selectors(selectClass(testClassName))
            .filters(includeTags("jazzer"));
    if (!Opt.targetMethod.get().isEmpty()) {
      // HACK: This depends on JUnit internals as we need to filter by method name without having to
      // specify the parameter types of the method.
      requestBuilder.filters(
          (PostDiscoveryFilter)
              testDescriptor ->
                  includedIf(
                      !(testDescriptor instanceof MethodBasedTestDescriptor)
                          || ((MethodBasedTestDescriptor) testDescriptor)
                              .getTestMethod()
                              .getName()
                              .equals(Opt.targetMethod.get())));
    }
    LauncherDiscoveryRequest request = requestBuilder.build();
    Launcher launcher = LauncherFactory.create(config);
    TestPlan testPlan = launcher.discover(request);
    if (!testPlan.containsTests()) {
      return Optional.empty();
    }
    return Optional.of(new JUnitRunner(launcher, testPlan));
  }

  public int run() {
    AtomicReference<TestExecutionResult> testResultHolder = new AtomicReference<>();
    AtomicBoolean sawContainerFailure = new AtomicBoolean();
    launcher.execute(
        testPlan,
        new TestExecutionListener() {
          @Override
          public void executionFinished(
              TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (testIdentifier.isTest()) {
              testResultHolder.set(testExecutionResult);
            } else {
              // Lifecycle methods can fail too, which results in failed execution results on
              // container nodes. We emit all these failures as errors, not findings, since the
              // lifecycle methods invoked by JUnit, which don't include @BeforeEach and
              // @AfterEach executed during individual fuzz test executions, usually aren't
              // reproducible with any given input (e.g. @AfterAll methods).
              testExecutionResult
                  .getThrowable()
                  .map(ExceptionUtils::preprocessThrowable)
                  .ifPresent(
                      throwable -> {
                        sawContainerFailure.set(true);
                        Log.error(throwable);
                      });
            }
          }

          @Override
          public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
            entry.getKeyValuePairs().values().forEach(Log::info);
          }
        });

    TestExecutionResult result = testResultHolder.get();
    if (result == null) {
      // This can only happen if a test container failed, in which case we will have printed a
      // stack trace.
      Log.error("Failed to run fuzz test");
      return 1;
    }
    if (result.getStatus() != FAILED) {
      // We do not generate a finding for aborted tests (i.e. tests whose preconditions were not
      // met) as such tests also wouldn't make a test run fail.
      if (result.getStatus() == ABORTED) {
        Log.warn("Fuzz test aborted", result.getThrowable().orElse(null));
      }
      if (sawContainerFailure.get()) {
        // A failure in a test container indicates a setup error, so we don't return the finding
        // exit code in this case.
        return 1;
      }
      return 0;
    }

    // Safe to unwrap as in JUnit Jupiter, tests and containers always fail with a Throwable:
    // https://github.com/junit-team/junit5/blob/ac31e9a7d58973db73496244dab4defe17ae563e/junit-platform-engine/src/main/java/org/junit/platform/engine/support/hierarchical/ThrowableCollector.java#LL176C37-L176C37
    Throwable throwable = result.getThrowable().get();
    if (throwable instanceof ExitCodeException) {
      // libFuzzer exited with a non-zero exit code, but Jazzer didn't produce a finding. Forward
      // the exit code and assume that information has already been printed (e.g. a timeout).
      return ((ExitCodeException) throwable).exitCode;
    } else {
      // Non-fatal findings and exceptions in containers have already been printed, the fatal
      // finding is passed to JUnit as the test result.
      return JAZZER_FINDING_EXIT_CODE;
    }
  }
}
