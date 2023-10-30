/*
 * Copyright 2023 Code Intelligence GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.code_intelligence.jazzer.mutation.mutator.lang;

import static com.code_intelligence.jazzer.mutation.support.StreamSupport.getOrEmpty;
import static com.code_intelligence.jazzer.mutation.support.TypeSupport.asSubclassOrEmpty;
import static java.lang.String.format;
import static java.util.Arrays.stream;

import com.code_intelligence.jazzer.mutation.api.MutatorFactory;
import com.code_intelligence.jazzer.mutation.api.SerializingMutator;
import com.code_intelligence.jazzer.mutation.combinator.NewInstanceMutator;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

class RecordMutatorFactory extends MutatorFactory {

  private final HashMap<Class<?>, SerializingMutator<?>> internedMutators = new HashMap<>();

  @Override
  @SuppressWarnings({"unchecked", "rawtypes", "SimplifyStreamApiCallChains"})
  public Optional<SerializingMutator<?>> tryCreate(AnnotatedType type, MutatorFactory factory) {
    return asSubclassOrEmpty(type, Record.class)
        // The default constructor is guaranteed to exist on Records, containing all record
        // components.
        .map(recordClass -> recordClass.getDeclaredConstructors()[0])
        .flatMap(
            defaultConstructor -> {
              Class<Record> declaringClass = (Class<Record>) defaultConstructor.getDeclaringClass();
              // getRecordComponents returns the record components in their declaration order,
              // hence, they correspond to the default constructor parameters.
              List<Method> componentAccessors =
                  stream(declaringClass.getRecordComponents())
                      .map(RecordComponent::getAccessor)
                      .collect(Collectors.toList());

              // Use interned mutator to avoid recursive creation of mutators for nested records.
              if (internedMutators.containsKey(declaringClass)) {
                return Optional.of(internedMutators.get(declaringClass));
              }

              // Create a new record instance via the default constructor.
              // The provided values are the values of the record components and have to match
              // the order of the default constructor parameters.
              Function<Object[], Record> newInstance =
                  (Object[] values) -> {
                    try {
                      return (Record) defaultConstructor.newInstance(values);
                    } catch (ReflectiveOperationException e) {
                      throw new RuntimeException(
                          format(
                              "Could not create instance of %s via default constructor",
                              declaringClass.getName()),
                          e);
                    }
                  };

              // Getter function for all component values of the given record.
              Function<Record, Object[]> values =
                  (record) ->
                      componentAccessors.stream()
                          .map(
                              accessor -> {
                                try {
                                  return accessor.invoke(record);
                                } catch (ReflectiveOperationException e) {
                                  throw new RuntimeException(e);
                                }
                              })
                          .toArray();

              // Supplier for all record component mutators. If not all mutators can be created,
              // an exception is thrown and the record mutator creation aborted.
              Supplier<SerializingMutator[]> mutators =
                  () ->
                      // Try to create mutators for every parameter of the default constructor,
                      // bail if not possible and handle
                      stream(defaultConstructor.getAnnotatedParameterTypes())
                          .map(factory::createOrThrow)
                          .toArray(SerializingMutator[]::new);

              return getOrEmpty(
                  () ->
                      new NewInstanceMutator<>(
                          declaringClass,
                          newInstance,
                          values,
                          mutators,
                          mutator -> internedMutators.put(declaringClass, mutator)));
            });
  }
}
