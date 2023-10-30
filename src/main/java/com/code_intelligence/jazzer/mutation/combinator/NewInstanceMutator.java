/*
 * Copyright 2023 Code Intelligence GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.code_intelligence.jazzer.mutation.combinator;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

import com.code_intelligence.jazzer.mutation.api.Debuggable;
import com.code_intelligence.jazzer.mutation.api.PseudoRandom;
import com.code_intelligence.jazzer.mutation.api.SerializingMutator;
import com.code_intelligence.jazzer.mutation.api.Sizeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The {@link NewInstanceMutator} creates new instances of a given class via the provided {@code
 * newInstance} {@link Function}, passing in all required parameters previously acquired via the
 * {@code values} {@link Function}. The {@code mutators} {@link Supplier} provides corresponding
 * mutators for all value / parameter types.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class NewInstanceMutator<T> extends SerializingMutator<T> {
  private final Class<T> clazz;
  private final Function<Object[], T> newInstance;
  private final Function<T, Object[]> values;
  private final SerializingMutator[] mutators;

  public NewInstanceMutator(
      Class<T> clazz,
      Function<Object[], T> newInstance,
      Function<T, Object[]> values,
      Supplier<SerializingMutator[]> mutatorsSupplier,
      Consumer<SerializingMutator<T>> registerSelf) {
    registerSelf.accept(this);
    this.clazz = clazz;
    this.newInstance = newInstance;
    this.values = values;
    this.mutators = mutatorsSupplier.get();
  }

  @Override
  public T read(DataInputStream in) throws IOException {
    Object[] values = new Object[mutators.length];
    for (int i = 0; i < mutators.length; i++) {
      values[i] = mutators[i].read(in);
    }
    return newInstance.apply(values);
  }

  @Override
  public void write(T value, DataOutputStream out) throws IOException {
    Object[] values = getComponentValues(value);
    for (int i = 0; i < mutators.length; i++) {
      mutators[i].write(values[i], out);
    }
  }

  @Override
  public T init(PseudoRandom prng) {
    Object[] values = new Object[mutators.length];
    for (int i = 0; i < mutators.length; i++) {
      values[i] = mutators[i].init(prng);
    }
    return newInstance.apply(values);
  }

  @Override
  public T detach(T obj) {
    return newInstance.apply(getComponentValues(obj));
  }

  @Override
  public T mutate(T value, PseudoRandom prng) {
    if (mutators.length == 0) {
      return value;
    }
    Object[] values = getComponentValues(value);
    int index = prng.indexIn(mutators);
    values[index] = mutators[index].mutate(values[index], prng);
    return newInstance.apply(values);
  }

  @Override
  public T crossOver(T value, T otherValue, PseudoRandom prng) {
    if (mutators.length == 0) {
      return value;
    }
    Object[] componentValues = getComponentValues(value);
    Object[] otherComponentValues = getComponentValues(otherValue);
    int i = prng.indexIn(mutators);
    componentValues[i] = mutators[i].crossOver(componentValues[i], otherComponentValues[i], prng);
    return newInstance.apply(componentValues);
  }

  @Override
  public boolean hasFixedSizeInt(Predicate<Sizeable> isInCycle) {
    return stream(mutators).allMatch(mutator -> mutator.hasFixedSize(isInCycle));
  }

  @Override
  public String toDebugString(Predicate<Debuggable> isInCycle) {
    if (isInCycle.test(this)) {
      return "(cycle)";
    } else {
      return format(
          "%s(%s)",
          clazz.getName(),
          stream(mutators).map(mutator -> mutator.toDebugString(isInCycle)).collect(joining(", ")));
    }
  }

  private Object[] getComponentValues(T value) {
    Object[] values = this.values.apply(value);
    for (int i = 0; i < mutators.length; i++) {
      values[i] = mutators[i].detach(values[i]);
    }
    return values;
  }
}
