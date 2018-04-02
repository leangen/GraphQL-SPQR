package io.leangen.graphql.metadata.strategy.query;

import graphql.execution.batched.Batched;
import io.leangen.graphql.annotations.GraphQLComplexity;
import io.leangen.graphql.metadata.Resolver;
import io.leangen.graphql.metadata.execution.MethodInvoker;
import io.leangen.graphql.metadata.execution.SingletonMethodInvoker;
import io.leangen.graphql.util.ClassUtils;
import io.leangen.graphql.util.Utils;
import org.reactivestreams.Publisher;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A resolver builder that exposes all public methods
 */
@SuppressWarnings("WeakerAccess")
public class PublicResolverBuilder extends FilteredResolverBuilder {

    private String[] basePackages;
    private boolean javaDeprecation;
    private Function<Method, String> descriptionMapper = method -> "";
    private Function<Method, String> deprecationReasonMapper = method -> javaDeprecation && method.isAnnotationPresent(Deprecated.class) ? "" : null;

    public PublicResolverBuilder() {
        this(new String[0]);
    }

    public PublicResolverBuilder(String... basePackages) {
        this.operationNameGenerator = new MethodOperationNameGenerator();
        this.argumentBuilder = new AnnotatedArgumentBuilder();
        withBasePackages(basePackages);
        withJavaDeprecationRespected(true);
        withDefaultFilters();
    }

    public PublicResolverBuilder withBasePackages(String... basePackages) {
        this.basePackages = basePackages;
        return this;
    }

    public PublicResolverBuilder withJavaDeprecationRespected(boolean javaDeprecation) {
        this.javaDeprecation = javaDeprecation;
        return this;
    }

    public PublicResolverBuilder withDescriptionMapper(Function<Method, String> descriptionMapper) {
        this.descriptionMapper = descriptionMapper;
        return this;
    }

    public PublicResolverBuilder withDeprecationReasonMapper(Function<Method, String> deprecationReasonMapper) {
        this.deprecationReasonMapper = deprecationReasonMapper;
        return this;
    }

    @Override
    public Collection<Resolver> buildQueryResolvers(ResolverBuilderParams params) {
        return buildResolvers(params, this::isQuery, operationNameGenerator::generateQueryName, true);
    }

    @Override
    public Collection<Resolver> buildMutationResolvers(ResolverBuilderParams params) {
        return buildResolvers(params, this::isMutation, operationNameGenerator::generateMutationName, false);
    }

    @Override
    public Collection<Resolver> buildSubscriptionResolvers(ResolverBuilderParams params) {
        return buildResolvers(params, this::isSubscription, operationNameGenerator::generateSubscriptionName, false);
    }

    private Collection<Resolver> buildResolvers(ResolverBuilderParams params, Predicate<Method> filter, NameGenerator nameGenerator, boolean batchable) {
        AnnotatedType beanType = params.getBeanType();
        Object querySourceBean = params.getQuerySourceBean();
        Class<?> rawType = ClassUtils.getRawType(beanType.getType());
        if (rawType.isArray() || rawType.isPrimitive()) return Collections.emptyList();
        return Arrays.stream(rawType.getMethods())
                .filter(method -> isPackageAcceptable(method, rawType, params.getBasePackages()))
                .filter(filter)
                .filter(method -> params.getInclusionStrategy().includeOperation(method, getReturnType(method, params)))
                .filter(getFilters().stream().reduce(Predicate::and).orElse(ACCEPT_ALL))
                .map(method -> new Resolver(
                        nameGenerator.name(method, beanType, querySourceBean),
                        descriptionMapper.apply(method),
                        deprecationReasonMapper.apply(method),
                        batchable && method.isAnnotationPresent(Batched.class),
                        querySourceBean == null ? new MethodInvoker(method, beanType) : new SingletonMethodInvoker(querySourceBean, method, beanType),
                        getReturnType(method, params),
                        argumentBuilder.buildResolverArguments(
                                new ArgumentBuilderParams(method, beanType, params.getInclusionStrategy(), params.getTypeTransformer())),
                        method.isAnnotationPresent(GraphQLComplexity.class) ? method.getAnnotation(GraphQLComplexity.class).value() : null
                ))
                .collect(Collectors.toList());
    }

    protected boolean isQuery(Method method) {
        return !isMutation(method) && !isSubscription(method);
    }

    protected boolean isMutation(Method method) {
        return method.getReturnType() == void.class;
    }

    protected boolean isSubscription(Method method) {
        return method.getReturnType() == Publisher.class;
    }

    protected boolean isPackageAcceptable(Method method, Class<?> beanType, String[] defaultPackages) {
        String[] basePackages = new String[0];
        if (Utils.isArrayNotEmpty(this.basePackages)) {
            basePackages = this.basePackages;
        } else if (Utils.isArrayNotEmpty(defaultPackages)) {
            basePackages = defaultPackages;
        } else if (beanType.getPackage() != null) {
            basePackages = new String[] {beanType.getPackage().getName()};
        }
        basePackages = Arrays.stream(basePackages).filter(Utils::isNotEmpty).toArray(String[]::new); //remove the default package
        return method.getDeclaringClass().equals(beanType)
                || Arrays.stream(basePackages).anyMatch(basePackage -> ClassUtils.isSubPackage(method.getDeclaringClass().getPackage(), basePackage));
    }
}
