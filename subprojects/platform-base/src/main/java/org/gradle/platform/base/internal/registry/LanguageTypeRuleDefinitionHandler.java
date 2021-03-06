/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.platform.base.internal.registry;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.reflect.JavaReflectionUtil;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.language.base.LanguageSourceSet;
import org.gradle.language.base.internal.registry.LanguageRegistry;
import org.gradle.language.base.internal.registry.RuleBasedLanguageRegistration;
import org.gradle.language.base.plugins.ComponentModelBasePlugin;
import org.gradle.language.base.sources.BaseLanguageSourceSet;
import org.gradle.model.InvalidModelRuleDeclarationException;
import org.gradle.model.internal.core.Inputs;
import org.gradle.model.internal.core.ModelMutator;
import org.gradle.model.internal.core.ModelReference;
import org.gradle.model.internal.core.MutableModelNode;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.inspect.MethodRuleDefinition;
import org.gradle.model.internal.inspect.RuleSourceDependencies;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.model.internal.type.ModelType;
import org.gradle.platform.base.InvalidModelException;
import org.gradle.platform.base.LanguageType;
import org.gradle.platform.base.LanguageTypeBuilder;
import org.gradle.platform.base.internal.builder.LanguageTypeBuilderInternal;
import org.gradle.platform.base.internal.rules.RuleContext;
import org.gradle.platform.base.internal.util.ImplementationTypeDetermer;

import java.util.List;

public class LanguageTypeRuleDefinitionHandler extends AbstractAnnotationDrivenMethodComponentRuleDefinitionHandler<LanguageType> {

    private final String modelName;
    private final ModelType<LanguageSourceSet> baseInterface;
    private final Factory<? extends LanguageTypeBuilderInternal<LanguageSourceSet>> typeBuilderFactory;

    public ImplementationTypeDetermer<LanguageSourceSet, BaseLanguageSourceSet> implementationTypeDetermer = new ImplementationTypeDetermer<LanguageSourceSet, BaseLanguageSourceSet>("language", BaseLanguageSourceSet.class);

    public LanguageTypeRuleDefinitionHandler() {
        this.modelName = "language";
        this.typeBuilderFactory = JavaReflectionUtil.factory(new DirectInstantiator(), DefaultLanguageTypeBuilder.class);
        this.baseInterface = ModelType.of(LanguageSourceSet.class);

    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <R> void doRegister(MethodRuleDefinition<R> ruleDefinition, ModelRegistry modelRegistry, RuleSourceDependencies dependencies, ModelType<? extends LanguageSourceSet> type, LanguageTypeBuilderInternal<? extends LanguageSourceSet> builder) {
        ModelType<? extends BaseLanguageSourceSet> implementation = implementationTypeDetermer.determineImplementationType(type, builder);
        dependencies.add(ComponentModelBasePlugin.class);
        if (implementation != null) {
            ModelMutator<?> mutator = new RegisterTypeRule<LanguageSourceSet, BaseLanguageSourceSet>(type, implementation, builder.getLanguageName(), ruleDefinition.getDescriptor(), new RegistrationAction());
            modelRegistry.mutate(mutator);
        }
    }


    @Override
    public <R> void register(MethodRuleDefinition<R> ruleDefinition, ModelRegistry modelRegistry, RuleSourceDependencies dependencies) {
        try {
            ModelType<? extends LanguageSourceSet> type = readType(ruleDefinition);
            LanguageTypeBuilderInternal<? extends LanguageSourceSet> builder = typeBuilderFactory.create();

            ruleDefinition.getRuleInvoker().invoke(builder);

            doRegister(ruleDefinition, modelRegistry, dependencies, type, builder);
        } catch (InvalidModelException e) {
            invalidModelRule(ruleDefinition, e);
        }
    }

    protected void invalidModelRule(MethodRuleDefinition<?> ruleDefinition, InvalidModelException e) {
        StringBuilder sb = new StringBuilder();
        ruleDefinition.getDescriptor().describeTo(sb);
        sb.append(String.format(" is not a valid %s model rule method.", modelName));
        throw new InvalidModelRuleDeclarationException(sb.toString(), e);
    }


    @SuppressWarnings("rawtypes")
    protected ModelType<? extends LanguageSourceSet> readType(MethodRuleDefinition<?> ruleDefinition) {
        assertIsVoidMethod(ruleDefinition);
        ModelType<LanguageTypeBuilder> buildInterfaceModelType = ModelType.of(LanguageTypeBuilder.class);
        if (ruleDefinition.getReferences().size() != 1) {
            throw new InvalidModelException(String.format("Method %s must have a single parameter of type '%s'.", getDescription(), buildInterfaceModelType.toString()));
        }
        ModelType<?> builder = ruleDefinition.getReferences().get(0).getType();
        if (!buildInterfaceModelType.isAssignableFrom(builder)) {
            throw new InvalidModelException(String.format("Method %s must have a single parameter of type '%s'.", getDescription(), buildInterfaceModelType.toString()));
        }
        if (builder.getTypeVariables().size() != 1) {
            throw new InvalidModelException(String.format("Parameter of type '%s' must declare a type parameter.", buildInterfaceModelType.toString()));
        }
        ModelType<?> subType = builder.getTypeVariables().get(0);

        if (subType.isWildcard()) {
            throw new InvalidModelException(String.format("%s type '%s' cannot be a wildcard type (i.e. cannot use ? super, ? extends etc.).", StringUtils.capitalize(modelName), subType.toString()));
        }

        ModelType<? extends LanguageSourceSet> asSubclass = baseInterface.asSubclass(subType);
        if (asSubclass == null) {
            throw new InvalidModelException(String.format("%s type '%s' is not a subtype of '%s'.", StringUtils.capitalize(modelName), subType.toString(), baseInterface.toString()));
        }

        return asSubclass;
    }

    public static class DefaultLanguageTypeBuilder extends AbstractTypeBuilder<LanguageSourceSet> implements LanguageTypeBuilderInternal<LanguageSourceSet> {
        private String languageName;

        public DefaultLanguageTypeBuilder() {
            super(LanguageType.class);
        }

        @Override
        public void setLanguageName(String languageName) {
            this.languageName = languageName;
        }

        @Override
        public String getLanguageName() {
            return languageName;
        }
    }

    private static class RegistrationAction<T extends LanguageSourceSet, U extends BaseLanguageSourceSet> implements Action<RegistrationContext<T, U>> {
        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void execute(final RegistrationContext<T, U> context) {
            LanguageRegistry languageRegistry = context.getLanguageRegistry();
            languageRegistry.add(new RuleBasedLanguageRegistration(context.languageName, context.type.getConcreteClass(), context.implementation.getConcreteClass(), context.getInstantiator(), context.fileResolver));
        }
    }

    protected static class RegistrationContext<T, U> {
        private final ModelType<? extends T> type;
        private final ModelType<? extends U> implementation;
        private final LanguageRegistry languageRegistry;
        private final Instantiator instantiator;
        private final FileResolver fileResolver;
        private final String languageName;

        public RegistrationContext(ModelType<? extends T> type, ModelType<? extends U> implementation, LanguageRegistry languageRegistry, Instantiator instantiator, FileResolver fileResolver, String languageName) {
            this.type = type;
            this.implementation = implementation;
            this.languageRegistry = languageRegistry;
            this.instantiator = instantiator;
            this.fileResolver = fileResolver;
            this.languageName = languageName;
        }

        public ModelType<? extends T> getType() {
            return type;
        }

        public ModelType<? extends U> getImplementation() {
            return implementation;
        }

        public LanguageRegistry getLanguageRegistry() {
            return languageRegistry;
        }

        public Instantiator getInstantiator() {
            return instantiator;
        }

        public FileResolver getFileResolver() {
            return fileResolver;
        }
    }

    protected static class RegisterTypeRule<T, U> implements ModelMutator<LanguageRegistry> {
        private final ModelType<? extends T> type;
        private final ModelType<? extends U> implementation;
        private String languageName;
        private final ModelRuleDescriptor descriptor;
        private final ModelReference<LanguageRegistry> subject;
        private final List<ModelReference<?>> inputs;
        private final Action<? super RegistrationContext<T, U>> registerAction;

        protected RegisterTypeRule(ModelType<? extends T> type, ModelType<? extends U> implementation, String languageName, ModelRuleDescriptor descriptor, Action<? super RegistrationContext<T, U>> registerAction) {
            this.type = type;
            this.implementation = implementation;
            this.languageName = languageName;
            this.descriptor = descriptor;
            this.registerAction = registerAction;

            subject = ModelReference.of(LanguageRegistry.class);
            inputs = ImmutableList.<ModelReference<?>>of(ModelReference.of(ServiceRegistry.class));
        }

        public ModelReference<LanguageRegistry> getSubject() {
            return subject;
        }

        public List<ModelReference<?>> getInputs() {
            return inputs;
        }

        public ModelRuleDescriptor getDescriptor() {
            return descriptor;
        }

        public final void mutate(MutableModelNode modelNode, final LanguageRegistry languageRegistry, final Inputs inputs) {
            RuleContext.inContext(getDescriptor(), new Runnable() {
                public void run() {
                    ServiceRegistry serviceRegistry = inputs.get(0, ModelType.of(ServiceRegistry.class)).getInstance();
                    Instantiator instantiator = serviceRegistry.get(Instantiator.class);
                    FileResolver fileResolver = serviceRegistry.get(FileResolver.class);
                    RegistrationContext<T, U> context = new RegistrationContext<T, U>(type, implementation, languageRegistry, instantiator, fileResolver, languageName);
                    registerAction.execute(context);
                }
            });
        }
    }
}
