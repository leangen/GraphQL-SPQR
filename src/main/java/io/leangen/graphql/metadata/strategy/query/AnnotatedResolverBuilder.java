package io.leangen.graphql.metadata.strategy.query;

import graphql.execution.batched.Batched;
import io.leangen.graphql.annotations.GraphQLComplexity;
import io.leangen.graphql.annotations.GraphQLMutation;
import io.leangen.graphql.annotations.GraphQLQuery;
import io.leangen.graphql.annotations.GraphQLSubscription;
import io.leangen.graphql.metadata.Resolver;
import io.leangen.graphql.metadata.execution.FieldAccessor;
import io.leangen.graphql.metadata.execution.MethodInvoker;
import io.leangen.graphql.metadata.execution.SingletonMethodInvoker;
import io.leangen.graphql.metadata.strategy.InclusionStrategy;
import io.leangen.graphql.util.ClassUtils;
import io.leangen.graphql.util.Utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A resolver builder that exposes only the methods explicitly annotated with {@link GraphQLQuery}
 */
public class AnnotatedResolverBuilder extends FilteredResolverBuilder {

    public AnnotatedResolverBuilder() {
        this.operationNameGenerator = new DelegatingOperationNameGenerator(new AnnotatedOperationNameGenerator(), new MethodOperationNameGenerator());
        this.argumentBuilder = new AnnotatedArgumentBuilder();
        withDefaultFilters();
    }

    @Override
    public Collection<Resolver> buildQueryResolvers(ResolverBuilderParams params) {
        Stream<Resolver> methodInvokers =
                buildResolvers(params, GraphQLQuery.class, operationNameGenerator::generateQueryName, true).stream();

        AnnotatedType beanType = params.getBeanType();
        Stream<Resolver> fieldAccessors = ClassUtils.getAnnotatedFields(ClassUtils.getRawType(beanType.getType()), GraphQLQuery.class).stream()
                .filter(getFilters().stream().reduce(Predicate::and).orElse(ACCEPT_ALL))
                .filter(field -> params.getInclusionStrategy().includeOperation(field, getFieldType(field, params)))
                .map(field -> new Resolver(
                        operationNameGenerator.generateQueryName(field, beanType, params.getQuerySourceBean()),
                        field.getAnnotation(GraphQLQuery.class).description(),
                        Utils.decodeNullable(field.getAnnotation(GraphQLQuery.class).deprecationReason()),
                        false,
                        new FieldAccessor(field, beanType),
                        getFieldType(field, params),
                        Collections.emptyList(),
                        field.isAnnotationPresent(GraphQLComplexity.class) ? field.getAnnotation(GraphQLComplexity.class).value() : null
                ));
        return Stream.concat(methodInvokers, fieldAccessors).collect(Collectors.toSet());
    }

    @Override
    public Collection<Resolver> buildMutationResolvers(ResolverBuilderParams params) {
        return buildResolvers(params, GraphQLMutation.class, operationNameGenerator::generateMutationName, false);
    }

    @Override
    public Collection<Resolver> buildSubscriptionResolvers(ResolverBuilderParams params) {
        return buildResolvers(params, GraphQLSubscription.class, operationNameGenerator::generateSubscriptionName, false);
    }

    private Collection<Resolver> buildResolvers(ResolverBuilderParams params, Class<? extends Annotation> annotation,
                                                NameGenerator nameGenerator, boolean batchable) {

        AnnotatedType beanType = params.getBeanType();
        Object querySourceBean = params.getQuerySourceBean();
        InclusionStrategy inclusionStrategy = params.getInclusionStrategy();
        return ClassUtils.getAnnotatedMethods(ClassUtils.getRawType(beanType.getType()), annotation).stream()
                .filter(getFilters().stream().reduce(Predicate::and).orElse(ACCEPT_ALL))
                .filter(method -> inclusionStrategy.includeOperation(method, getReturnType(method, params)))
                .map(method -> new Resolver(
                        nameGenerator.name(method, beanType, querySourceBean),
                        description(method.getAnnotation(annotation)),
                        Utils.decodeNullable(deprecationReason(method.getAnnotation(annotation))),
                        batchable && method.isAnnotationPresent(Batched.class),
                        querySourceBean == null ? new MethodInvoker(method, beanType) : new SingletonMethodInvoker(querySourceBean, method, beanType),
                        getReturnType(method, params),
                        argumentBuilder.buildResolverArguments(new ArgumentBuilderParams(method, beanType, inclusionStrategy, params.getTypeTransformer())),
                        method.isAnnotationPresent(GraphQLComplexity.class) ? method.getAnnotation(GraphQLComplexity.class).value() : null
                )).collect(Collectors.toSet());
    }

    private String description(Annotation annotation) {
        if (annotation instanceof GraphQLQuery) {
            return ((GraphQLQuery) annotation).description();
        }
        if (annotation instanceof GraphQLMutation) {
            return ((GraphQLMutation) annotation).description();
        }
        if (annotation instanceof GraphQLSubscription) {
            return ((GraphQLSubscription) annotation).description();
        }
        throw new IllegalArgumentException("Invalid operation annotations " + annotation);
    }

    private String deprecationReason(Annotation annotation) {
        if (annotation instanceof GraphQLQuery) {
            return ((GraphQLQuery) annotation).deprecationReason();
        }
        if (annotation instanceof GraphQLMutation) {
            return ((GraphQLMutation) annotation).deprecationReason();
        }
        if (annotation instanceof GraphQLSubscription) {
            return ((GraphQLSubscription) annotation).deprecationReason();
        }
        throw new IllegalArgumentException("Invalid operation annotations " + annotation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationNameGenerator.getClass(), argumentBuilder.getClass());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AnnotatedResolverBuilder)) return false;
        AnnotatedResolverBuilder that = (AnnotatedResolverBuilder) other;
        return this.operationNameGenerator.getClass().equals(that.operationNameGenerator.getClass())
                && this.argumentBuilder.getClass().equals(that.argumentBuilder.getClass());
    }
}
