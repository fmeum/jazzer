/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * By downloading, you agree to the Code Intelligence Jazzer Terms and Conditions.
 *
 * The Code Intelligence Jazzer Terms and Conditions are provided in LICENSE-JAZZER.txt
 * located in the root directory of the project.
 */

package com.code_intelligence.jazzer.mutation.mutator.lang;

import static com.code_intelligence.jazzer.mutation.support.TestSupport.createOrThrow;
import static com.code_intelligence.jazzer.mutation.support.TestSupport.mockPseudoRandom;
import static com.google.common.truth.Truth.assertThat;

import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.code_intelligence.jazzer.mutation.api.SerializingMutator;
import com.code_intelligence.jazzer.mutation.engine.ChainedMutatorFactory;
import com.code_intelligence.jazzer.mutation.support.TestSupport.MockPseudoRandom;
import com.code_intelligence.jazzer.mutation.support.TestSupport.ParameterHolder;
import com.code_intelligence.jazzer.mutation.support.TypeHolder;
import java.lang.reflect.AnnotatedType;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class NullableMutatorTest {
  ChainedMutatorFactory factory;

  @BeforeEach
  void createFactory() {
    factory =
        ChainedMutatorFactory.of(
            Stream.of(new NullableMutatorFactory(), new BooleanMutatorFactory()));
  }

  @Test
  void testNullable() {
    SerializingMutator<Boolean> mutator = createOrThrow(factory, new TypeHolder<Boolean>() {});
    assertThat(mutator.toString()).isEqualTo("Nullable<Boolean>");

    Boolean bool;
    try (MockPseudoRandom prng = mockPseudoRandom(/* init to null */ true)) {
      bool = mutator.init(prng);
    }
    assertThat(bool).isNull();

    try (MockPseudoRandom prng = mockPseudoRandom(/* init for non-null Boolean */ false)) {
      bool = mutator.mutate(bool, prng);
    }
    assertThat(bool).isFalse();

    try (MockPseudoRandom prng = mockPseudoRandom(/* mutate to non-null Boolean */ false)) {
      bool = mutator.mutate(bool, prng);
    }
    assertThat(bool).isTrue();

    try (MockPseudoRandom prng = mockPseudoRandom(/* mutate to null */ true)) {
      bool = mutator.mutate(bool, prng);
    }
    assertThat(bool).isNull();
  }

  @Test
  void testNotNull() {
    AnnotatedType notNullBoolean = new TypeHolder<@NotNull Boolean>() {}.annotatedType();
    SerializingMutator<Boolean> mutator =
        (SerializingMutator<Boolean>) factory.createOrThrow(notNullBoolean);
    assertThat(mutator.toString()).isEqualTo("Boolean");
  }

  @Test
  void testPrimitive() {
    SerializingMutator<Boolean> mutator =
        (SerializingMutator<Boolean>)
            factory.createOrThrow(
                new ParameterHolder() {
                  void singleParam(boolean parameter) {}
                }.annotatedType());
    assertThat(mutator.toString()).isEqualTo("Boolean");
  }

  @Test
  void testCrossOver() {
    SerializingMutator<Boolean> mutator = createOrThrow(factory, new TypeHolder<Boolean>() {});
    try (MockPseudoRandom prng = mockPseudoRandom(true)) {
      Boolean valueCrossedOver = mutator.crossOver(Boolean.TRUE, Boolean.TRUE, prng);
      assertThat(valueCrossedOver).isNotNull();
    }
    try (MockPseudoRandom prng = mockPseudoRandom()) {
      Boolean bothNull = mutator.crossOver(null, null, prng);
      assertThat(bothNull).isNull();
    }
    try (MockPseudoRandom prng = mockPseudoRandom(false)) {
      Boolean oneNotNull = mutator.crossOver(null, Boolean.TRUE, prng);
      assertThat(oneNotNull).isNotNull();
    }
    try (MockPseudoRandom prng = mockPseudoRandom(true)) {
      Boolean nullFrequency = mutator.crossOver(null, Boolean.TRUE, prng);
      assertThat(nullFrequency).isNull();
    }
  }
}
