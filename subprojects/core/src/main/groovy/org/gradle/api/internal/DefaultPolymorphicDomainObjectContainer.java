/*
 * Copyright 2013 the original author or authors.
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
package org.gradle.api.internal;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import groovy.lang.Closure;
import org.gradle.api.*;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.model.internal.core.NamedEntityInstantiator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultPolymorphicDomainObjectContainer<T> extends AbstractPolymorphicDomainObjectContainer<T>
        implements ExtensiblePolymorphicDomainObjectContainer<T> {
    protected final Map<Class<? extends T>, NamedDomainObjectFactory<? extends T>> factories = Maps.newHashMap();
    private final NamedEntityInstantiator<T> instantiator = new DefaultNamedEntityInstantiator();

    public DefaultPolymorphicDomainObjectContainer(Class<T> type, Instantiator instantiator, Namer<? super T> namer) {
        super(type, instantiator, namer);
    }

    public DefaultPolymorphicDomainObjectContainer(Class<T> type, Instantiator instantiator) {
        this(type, instantiator, Named.Namer.forType(type));
    }

    @Override
    public NamedEntityInstantiator<T> getEntityInstantiator() {
        return instantiator;
    }

    protected T doCreate(String name) {
        NamedDomainObjectFactory<? extends T> factory = factories.get(getType());
        if (factory == null) {
            throw new InvalidUserDataException(String.format("Cannot create a %s named '%s' because this container "
                    + "does not support creating elements by name alone. Please specify which subtype of %s to create. "
                    + "Known subtypes are: %s", getTypeDisplayName(), name, getTypeDisplayName(), getSupportedTypeNames()));
        }
        return factory.create(name);
    }

    protected <U extends T> U doCreate(String name, Class<U> type) {
        @SuppressWarnings("unchecked")
        NamedDomainObjectFactory<U> factory = (NamedDomainObjectFactory<U>) factories.get(type);
        if (factory == null) {
            throw new InvalidUserDataException(String.format("Cannot create a %s because this type is not known "
                    + "to this container. Known types are: %s", type.getSimpleName(), getSupportedTypeNames()));
        }
        return factory.create(name);
    }

    public <U extends T> void registerDefaultFactory(NamedDomainObjectFactory<U> factory) {
        factories.put(getType(), factory);
    }

    public <U extends T> void registerFactory(Class<U> type, NamedDomainObjectFactory<? extends U> factory) {
        if (!getType().isAssignableFrom(type)) {
            throw new IllegalArgumentException(String.format("Cannot register a factory for type %s because "
                    + "it is not a subtype of container element type %s.", type.getSimpleName(), getTypeDisplayName()));
        }
        if(factories.containsKey(type)){
            throw new GradleException(String.format("Cannot register a factory for type %s because "
                    + "a factory for this type is already registered.", type.getSimpleName()));
        }
        factories.put(type, factory);
    }

    public <U extends T> void registerFactory(Class<U> type, final Closure<? extends U> factory) {
        registerFactory(type, new NamedDomainObjectFactory<U>() {
            public U create(String name) {
                return factory.call(name);
            }
        });
    }

    public <U extends T> void registerBinding(Class<U> type, final Class<? extends U> implementationType) {
        registerFactory(type, new NamedDomainObjectFactory<U>() {
            boolean named = Named.class.isAssignableFrom(implementationType);
            public U create(String name) {
                return named ? getInstantiator().newInstance(implementationType, name)
                        : getInstantiator().newInstance(implementationType);
            }
        });
    }

    private String getSupportedTypeNames() {
        List<String> names = Lists.newArrayList();
        for (Class<?> clazz : factories.keySet()) {
            names.add(clazz.getSimpleName());
        }
        Collections.sort(names);
        return names.isEmpty() ? "(None)" : Joiner.on(", ").join(names);
    }

    public Set<? extends Class<? extends T>> getCreateableTypes() {
        return ImmutableSet.copyOf(factories.keySet());
    }

    private class DefaultNamedEntityInstantiator implements NamedEntityInstantiator<T> {
        @Override
        public <S extends T> S create(String name, Class<S> type) {
            return doCreate(name, type);
        }
    }
}
