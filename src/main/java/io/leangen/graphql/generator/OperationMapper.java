package io.leangen.graphql.generator;

import graphql.execution.batched.BatchedDataFetcher;
import graphql.relay.Relay;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.PropertyDataFetcher;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.graphql.annotations.GraphQLId;
import io.leangen.graphql.execution.OperationExecutor;
import io.leangen.graphql.generator.mapping.TypeMapper;
import io.leangen.graphql.generator.mapping.TypeMappingEnvironment;
import io.leangen.graphql.metadata.Directive;
import io.leangen.graphql.metadata.DirectiveArgument;
import io.leangen.graphql.metadata.InputField;
import io.leangen.graphql.metadata.Operation;
import io.leangen.graphql.metadata.OperationArgument;
import io.leangen.graphql.metadata.TypedElement;
import io.leangen.graphql.metadata.exceptions.MappingException;
import io.leangen.graphql.metadata.strategy.query.DirectiveBuilderParams;
import io.leangen.graphql.metadata.strategy.value.ValueMapper;
import io.leangen.graphql.util.ClassUtils;
import io.leangen.graphql.util.ContextUtils;
import io.leangen.graphql.util.Directives;
import io.leangen.graphql.util.GraphQLUtils;
import io.leangen.graphql.util.Urls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInputObjectField.newInputObjectField;
import static graphql.schema.GraphQLInputObjectType.newInputObject;
import static graphql.schema.GraphQLObjectType.newObject;
import static io.leangen.graphql.util.GraphQLUtils.CLIENT_MUTATION_ID;
import static io.leangen.graphql.util.GraphQLUtils.name;

/**
 * <p>Drives the work of mapping Java structures into their GraphQL representations.</p>
 * While the task of mapping types is delegated to instances of {@link io.leangen.graphql.generator.mapping.TypeMapper},
 * selection of mappers, construction and attachment of resolvers (modeled by {@link DataFetcher}s
 * in <a href=https://github.com/graphql-java/graphql-java>graphql-java</a>), and other universal tasks are encapsulated
 * in this class.
 */
public class OperationMapper {

    private final List<GraphQLFieldDefinition> queries; //The list of all mapped queries
    private final List<GraphQLFieldDefinition> mutations; //The list of all mapped mutations
    private final List<GraphQLFieldDefinition> subscriptions; //The list of all mapped subscriptions
    private final List<GraphQLDirective> directives; //The list of all added mapped directives

    private static final Logger log = LoggerFactory.getLogger(OperationMapper.class);

    /**
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     */
    public OperationMapper(String queryRoot, String mutationRoot, String subscriptionRoot, BuildContext buildContext) {
        this.queries = generateQueries(queryRoot, buildContext);
        this.mutations = generateMutations(mutationRoot, buildContext);
        this.subscriptions = generateSubscriptions(subscriptionRoot, buildContext);
        this.directives = generateDirectives(buildContext);
        buildContext.resolveTypeReferences();
    }

    /**
     * Generates {@link GraphQLFieldDefinition}s representing all top-level queries (with their types, arguments and sub-queries)
     * fully mapped. This is the entry point into the query-mapping logic.
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return A list of {@link GraphQLFieldDefinition}s representing all top-level queries
     */
    private List<GraphQLFieldDefinition> generateQueries(String queryRoot, BuildContext buildContext) {
        List<Operation> rootQueries = new ArrayList<>(buildContext.operationRegistry.getRootQueries());
        List<GraphQLFieldDefinition> queries = rootQueries.stream()
                .map(query -> toGraphQLField(queryRoot, query, buildContext))
                .collect(Collectors.toList());

        buildContext.resolveTypeReferences();
        //Add support for the Relay Node query only if an explicit one isn't already provided and Relay-enabled resolvers exist
        if (rootQueries.stream().noneMatch(query -> query.getName().equals(GraphQLUtils.NODE))) {
            Map<String, String> nodeQueriesByType = getNodeQueriesByType(rootQueries, queries, buildContext.typeRegistry, buildContext.node, buildContext);
            if (!nodeQueriesByType.isEmpty()) {
                queries.add(buildContext.relay.nodeField(buildContext.node, createNodeResolver(nodeQueriesByType, buildContext.relay)));
            }
        }
        return queries;
    }

    /**
     * Generates {@link GraphQLFieldDefinition}s representing all mutations (with their types, arguments and sub-queries)
     * fully mapped. By the GraphQL spec, all mutations are top-level only.
     * This is the entry point into the mutation-mapping logic.
     *
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return A list of {@link GraphQLFieldDefinition}s representing all mutations
     */
    private List<GraphQLFieldDefinition> generateMutations(String mutationRoot, BuildContext buildContext) {
        return buildContext.operationRegistry.getMutations().stream()
                .map(mutation -> buildContext.relayMappingConfig.relayCompliantMutations
                        ? toRelayMutation(mutationRoot, toGraphQLField(mutation, buildContext), createResolver(mutation, buildContext), buildContext)
                        : toGraphQLField(mutationRoot, mutation, buildContext))
                .collect(Collectors.toList());
    }

    private List<GraphQLFieldDefinition> generateSubscriptions(String subscriptionRoot, BuildContext buildContext) {
        return buildContext.operationRegistry.getSubscriptions().stream()
                .map(subscription -> toGraphQLField(subscriptionRoot, subscription, buildContext))
                .collect(Collectors.toList());
    }

    private List<GraphQLDirective> generateDirectives(BuildContext buildContext) {
        return buildContext.additionalDirectives.stream()
                .map(directiveType -> {
                    List<Class<?>> concreteSubTypes = ClassUtils.isAbstract(directiveType)
                            ? buildContext.abstractInputHandler.findConcreteSubTypes(ClassUtils.getRawType(directiveType.getType()), buildContext)
                            : Collections.emptyList();
                    return buildContext.directiveBuilder.buildClientDirective(directiveType, buildContext.directiveBuilderParams(concreteSubTypes));
                })
                .map(directive -> toGraphQLDirective(directive, buildContext))
                .collect(Collectors.toList());
    }

    private GraphQLFieldDefinition toGraphQLField(Operation operation, BuildContext buildContext) {
        GraphQLOutputType type = toGraphQLType(operation.getJavaType(), new TypeMappingEnvironment(operation.getTypedElement(), this, buildContext));
        GraphQLFieldDefinition.Builder fieldBuilder = newFieldDefinition()
                .name(operation.getName())
                .description(operation.getDescription())
                .deprecate(operation.getDeprecationReason())
                .type(type)
                .withDirective(Directives.mappedOperation(operation));

        toGraphQLDirectives(operation.getTypedElement(), buildContext.directiveBuilder::buildFieldDefinitionDirectives, buildContext)
                .forEach(fieldBuilder::withDirective);

        List<GraphQLArgument> arguments = operation.getArguments().stream()
                .filter(OperationArgument::isMappable)
                .map(argument -> toGraphQLArgument(argument, buildContext))
                .collect(Collectors.toList());
        fieldBuilder.arguments(arguments);
        if (GraphQLUtils.isRelayConnectionType(type) && buildContext.relayMappingConfig.strictConnectionSpec) {
            validateConnectionSpecCompliance(operation.getName(), arguments, buildContext.relay);
        }

        return buildContext.transformers.transform(fieldBuilder.build(), operation, this, buildContext);
    }

    /**
     * Maps a single operation to a GraphQL output field (as queries in GraphQL are nothing but fields of the root operation type).
     *
     * @param operation The operation to map to a GraphQL output field
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL output field representing the given operation
     */
    public GraphQLFieldDefinition toGraphQLField(String parentType, Operation operation, BuildContext buildContext) {
        GraphQLFieldDefinition field = toGraphQLField(operation, buildContext);
        DataFetcher<?> resolver = createResolver(operation, buildContext);
        buildContext.codeRegistry.dataFetcher(coordinates(parentType, field.getName()), resolver);
        return field;
    }

    public GraphQLOutputType toGraphQLType(AnnotatedType javaType, TypeMappingEnvironment env) {
        return toGraphQLType(javaType, new HashSet<>(), env);
    }

    /**
     * Maps a Java type to a GraphQL output type. Delegates most of the work to applicable
     * {@link io.leangen.graphql.generator.mapping.TypeMapper}s.
     * <p>See {@link TypeMapper#toGraphQLType(AnnotatedType, java.util.Set, TypeMappingEnvironment)}</p>
     *
     * @param javaType The Java type that is to be mapped to a GraphQL output type
     * @param env Contextual information about the current mapping
     *
     * @return GraphQL output type corresponding to the given Java type
     */
    public GraphQLOutputType toGraphQLType(AnnotatedType javaType, Set<Class<? extends TypeMapper>> mappersToSkip, TypeMappingEnvironment env) {
        env.addType(javaType);
        GraphQLOutputType type = env.buildContext.typeMappers.getTypeMapper(env.rootElement, javaType, mappersToSkip).toGraphQLType(javaType, mappersToSkip, env);
        log(env.buildContext.validator.checkUniqueness(type, env.rootElement, javaType));
        env.buildContext.typeCache.completeType(type);
        return type;
    }

    /**
     * Maps a single field/property to a GraphQL input field.
     *
     * @param inputField The field/property to map to a GraphQL input field
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return GraphQL input field representing the given field/property
     */
    public GraphQLInputObjectField toGraphQLInputField(InputField inputField, BuildContext buildContext) {
        GraphQLInputObjectField.Builder builder = newInputObjectField()
                .name(inputField.getName())
                .description(inputField.getDescription())
                .type(toGraphQLInputType(inputField.getJavaType(), new TypeMappingEnvironment(inputField.getTypedElement(), this, buildContext)))
                .withDirective(Directives.mappedInputField(inputField));

        toGraphQLDirectives(inputField.getTypedElement(), buildContext.directiveBuilder::buildInputFieldDefinitionDirectives, buildContext)
                .forEach(builder::withDirective);

        if (inputField.getDefaultValue().isSet()) {
            builder.defaultValue(inputField.getDefaultValue().getValue());
        }
        return buildContext.transformers.transform(builder.build(), inputField, this, buildContext);
    }

    public GraphQLInputType toGraphQLInputType(AnnotatedType javaType, TypeMappingEnvironment env) {
        return toGraphQLInputType(javaType, new HashSet<>(), env);
    }

    /**
     * Maps a Java type to a GraphQL input type. Delegates most of the work to applicable
     * {@link io.leangen.graphql.generator.mapping.TypeMapper}s.
     * <p>See {@link TypeMapper#toGraphQLInputType(AnnotatedType, java.util.Set, TypeMappingEnvironment)}</p>
     *
     * @param javaType The Java type that is to be mapped to a GraphQL input type
     * @param env Contextual information about the current mapping
     *
     * @return GraphQL input type corresponding to the given Java type
     */
    public GraphQLInputType toGraphQLInputType(AnnotatedType javaType, Set<Class<? extends TypeMapper>> mappersToSkip, TypeMappingEnvironment env) {
        env.addType(javaType);
        GraphQLInputType type = env.buildContext.typeMappers.getTypeMapper(env.rootElement, javaType, mappersToSkip).toGraphQLInputType(javaType, mappersToSkip, env);
        log(env.buildContext.validator.checkUniqueness(type, env.rootElement, javaType));
        return type;
    }

    private GraphQLArgument toGraphQLArgument(OperationArgument operationArgument, BuildContext buildContext) {
        GraphQLArgument.Builder builder = newArgument()
                .name(operationArgument.getName())
                .description(operationArgument.getDescription())
                .type(toGraphQLInputType(operationArgument.getJavaType(), new TypeMappingEnvironment(operationArgument.getTypedElement(), this, buildContext)));

        toGraphQLDirectives(operationArgument.getTypedElement(), buildContext.directiveBuilder::buildArgumentDefinitionDirectives, buildContext)
                .forEach(builder::withDirective);

        if (operationArgument.getDefaultValue().isSet()) {
            builder.defaultValue(operationArgument.getDefaultValue().getValue());
        }
        return buildContext.transformers.transform(builder.build(), operationArgument, this, buildContext);
    }

    private List<GraphQLDirective> toGraphQLDirectives(TypedElement element, BiFunction<AnnotatedElement, DirectiveBuilderParams, List<Directive>> directiveBuilder, BuildContext buildContext) {
        return element.getElements().stream()
                .flatMap(el -> directiveBuilder.apply(el, buildContext.directiveBuilderParams()).stream())
                .map(directive -> toGraphQLDirective(directive, buildContext))
                .collect(Collectors.toList());
    }

    public GraphQLDirective toGraphQLDirective(Directive directive, BuildContext buildContext) {
        GraphQLDirective.Builder builder = GraphQLDirective.newDirective()
                .name(directive.getName())
                .description(directive.getDescription())
                .validLocations(directive.getLocations());
        directive.getArguments().forEach(arg -> builder.argument(toGraphQLArgument(arg, buildContext)));
        return buildContext.transformers.transform(builder.build(), directive, this, buildContext);
    }

    private GraphQLArgument toGraphQLArgument(DirectiveArgument directiveArgument, BuildContext buildContext) {
        GraphQLArgument.Builder builder = newArgument()
                .name(directiveArgument.getName())
                .description(directiveArgument.getDescription())
                .type(toGraphQLInputType(directiveArgument.getJavaType(), new TypeMappingEnvironment(directiveArgument.getTypedElement(), this, buildContext)))
                .value(directiveArgument.getValue());

        toGraphQLDirectives(directiveArgument.getTypedElement(), buildContext.directiveBuilder::buildArgumentDefinitionDirectives, buildContext)
                .forEach(builder::withDirective);

        if (directiveArgument.getDefaultValue().isSet()) {
            builder.defaultValue(directiveArgument.getDefaultValue().getValue());
        }
        return buildContext.transformers.transform(builder.build(), directiveArgument, this, buildContext);
    }

    private GraphQLFieldDefinition toRelayMutation(String parentType, GraphQLFieldDefinition mutation, DataFetcher<?> resolver, BuildContext buildContext) {

        String payloadTypeName = mutation.getName() + "Payload";
        List<GraphQLFieldDefinition> outputFields;
        if (mutation.getType() instanceof GraphQLObjectType) {
            outputFields = ((GraphQLObjectType) mutation.getType()).getFieldDefinitions();
        } else {
            outputFields = new ArrayList<>();
            outputFields.add(GraphQLFieldDefinition.newFieldDefinition()
                    .name(buildContext.relayMappingConfig.wrapperFieldName)
                    .description(buildContext.relayMappingConfig.wrapperFieldDescription)
                    .type(mutation.getType())
                    .build());
            DataFetcher<?> wrapperFieldResolver = DataFetchingEnvironment::getSource;
            buildContext.codeRegistry.dataFetcher(coordinates(payloadTypeName, buildContext.relayMappingConfig.wrapperFieldName), wrapperFieldResolver);
        }
        List<GraphQLInputObjectField> inputFields = mutation.getArguments().stream()
                .map(arg -> {
                    GraphQLInputObjectField.Builder builder = newInputObjectField()
                            .name(arg.getName())
                            .description(arg.getDescription())
                            .type(arg.getType());
                    if (arg.hasSetDefaultValue()) {
                        builder.defaultValue(arg.getDefaultValue());
                    }
                    return builder.build();
                })
                .collect(Collectors.toList());

        GraphQLInputObjectType inputObjectType = newInputObject()
                .name(mutation.getName() + "Input")
                .field(newInputObjectField()
                        .name(CLIENT_MUTATION_ID)
                        .type(GraphQLString))
                .fields(inputFields)
                .build();

        GraphQLObjectType outputType = newObject()
                .name(payloadTypeName)
                .field(newFieldDefinition()
                        .name(CLIENT_MUTATION_ID)
                        .type(GraphQLString))
                .fields(outputFields)
                .build();

        DataFetcher<String> clientMutationIdResolver = env -> {
            if (ContextUtils.isDefault(env.getContext())) {
                return ContextUtils.getClientMutationId(env.getContext());
            } else {
                return new PropertyDataFetcher<String>(CLIENT_MUTATION_ID).get(env);
            }
        };
        buildContext.codeRegistry.dataFetcher(coordinates(payloadTypeName, CLIENT_MUTATION_ID), clientMutationIdResolver);

        final GraphQLFieldDefinition relayMutation = newFieldDefinition()
                .name(mutation.getName())
                .type(outputType)
                .argument(newArgument()
                        .name("input")
                        .type(new GraphQLNonNull(inputObjectType)))
                .build();

        DataFetcher<?> wrappedResolver = env -> {
            DataFetchingEnvironment innerEnv = new RelayDataFetchingEnvironmentDecorator(env);
            ContextUtils.setClientMutationId(env.getContext(), innerEnv.getArgument(CLIENT_MUTATION_ID));
            return resolver.get(innerEnv);
        };
        buildContext.codeRegistry.dataFetcher(coordinates(parentType, relayMutation.getName()), wrappedResolver);

        return relayMutation;
    }

    /**
     * Creates a generic resolver for the given operation.
     * @implSpec This resolver simply invokes {@link OperationExecutor#execute(DataFetchingEnvironment)}
     *
     * @param operation The operation for which the resolver is being created
     * @param buildContext The shared context containing all the global information needed for mapping
     *
     * @return The resolver for the given operation
     */
    @SuppressWarnings("deprecation")
    private DataFetcher<?> createResolver(Operation operation, BuildContext buildContext) {
        Stream<AnnotatedType> inputTypes = operation.getArguments().stream()
                .filter(OperationArgument::isMappable)
                .map(OperationArgument::getJavaType);
        ValueMapper valueMapper = buildContext.createValueMapper(inputTypes);

        if (operation.isBatched()) {
            return (BatchedDataFetcher) environment -> new OperationExecutor(operation, valueMapper, buildContext.globalEnvironment, buildContext.interceptorFactory).execute(environment);
        }
        return new OperationExecutor(operation, valueMapper, buildContext.globalEnvironment, buildContext.interceptorFactory)::execute;
    }

    /**
     * Creates a resolver for the <em>node</em> query as defined by the Relay GraphQL spec.
     * <p>This query only takes a singe argument called "id" of type String, and returns the object implementing the
     * <em>Node</em> interface to which the given id corresponds.</p>
     *
     * @param nodeQueriesByType A map of all queries whose return types implement the <em>Node</em> interface, keyed
     *                          by their corresponding GraphQL type name
     * @param relay Relay helper
     *
     * @return The node query resolver
     */
    private DataFetcher<?> createNodeResolver(Map<String, String> nodeQueriesByType, Relay relay) {
        return env -> {
            String typeName;
            try {
                typeName = relay.fromGlobalId(env.getArgument(GraphQLId.RELAY_ID_FIELD_NAME)).getType();
            } catch (Exception e) {
                throw new IllegalArgumentException(env.getArgument(GraphQLId.RELAY_ID_FIELD_NAME) + " is not a valid Relay node ID");
            }
            if (!nodeQueriesByType.containsKey(typeName)) {
                throw new IllegalArgumentException(typeName + " is not a Relay node type or no registered query can fetch it by ID");
            }
            final GraphQLObjectType queryRoot = env.getGraphQLSchema().getQueryType();
            final GraphQLFieldDefinition nodeQueryForType = queryRoot.getFieldDefinition(nodeQueriesByType.get(typeName));

            return env.getGraphQLSchema().getCodeRegistry().getDataFetcher(queryRoot, nodeQueryForType).get(env);
        };
    }

    private Map<String, String> getNodeQueriesByType(List<Operation> queries,
                                                     List<GraphQLFieldDefinition> graphQLQueries,
                                                     TypeRegistry typeRegistry, GraphQLInterfaceType node, BuildContext buildContext) {

        Map<String, String> nodeQueriesByType = new HashMap<>();

        for (int i = 0; i < queries.size(); i++) {
            Operation query = queries.get(i);
            GraphQLFieldDefinition graphQLQuery = graphQLQueries.get(i);

            if (graphQLQuery.getArgument(GraphQLId.RELAY_ID_FIELD_NAME) != null
                    && GraphQLUtils.isRelayId(graphQLQuery.getArgument(GraphQLId.RELAY_ID_FIELD_NAME))
                    && query.getResolver(GraphQLId.RELAY_ID_FIELD_NAME) != null) {

                GraphQLType unwrappedQueryType = GraphQLUtils.unwrapNonNull(graphQLQuery.getType());
                if (unwrappedQueryType instanceof GraphQLNamedType) {
                    GraphQLNamedType unwrappedOutput = (GraphQLNamedOutputType) unwrappedQueryType;
                    unwrappedQueryType = buildContext.typeCache.resolveType(unwrappedOutput.getName());
                    if (unwrappedQueryType instanceof GraphQLObjectType
                            && ((GraphQLObjectType) unwrappedQueryType).getInterfaces().contains(node)) {
                        nodeQueriesByType.put(unwrappedOutput.getName(), query.getName());
                    } else if (unwrappedQueryType instanceof GraphQLInterfaceType) {
                        typeRegistry.getOutputTypes(unwrappedOutput.getName()).stream()
                                .map(MappedType::getAsObjectType)
                                .filter(implementation -> implementation.getInterfaces().contains(node))
                                .forEach(nodeType -> nodeQueriesByType.putIfAbsent(nodeType.getName(), query.getName()));  //never override more precise resolvers
                    } else if (unwrappedQueryType instanceof GraphQLUnionType) {
                        typeRegistry.getOutputTypes(unwrappedOutput.getName()).stream()
                                .map(MappedType::getAsObjectType)
                                .filter(implementation -> implementation.getInterfaces().contains(node))
                                .filter(Directives::isMappedType)
                                // only register the possible types that can actually be returned from the primary resolver
                                // for interface-unions it is all the possible types but, for inline unions, only one (right?) possible type can match
                                .filter(implementation -> GenericTypeReflector.isSuperType(query.getResolver(GraphQLId.RELAY_ID_FIELD_NAME).getReturnType().getType(), Directives.getMappedType(implementation).getType()))
                                .forEach(nodeType -> nodeQueriesByType.putIfAbsent(nodeType.getName(), query.getName())); //never override more precise resolvers
                    }
                }
            }
        }
        return nodeQueriesByType;
    }

    private void log(Validator.ValidationResult result) {
        if (!result.isValid()) {
            log.warn(result.getMessage());
        }
    }

    private void validateConnectionSpecCompliance(String operationName, List<GraphQLArgument> arguments, Relay relay) {
        String errorMessageTemplate = "Operation '" + operationName + "' is incompatible with the Relay Connection spec due to %s. " +
                "If this is intentional, disable strict compliance checking. " +
                "For details and solutions see " + Urls.Errors.RELAY_CONNECTION_SPEC_VIOLATION;

        boolean forwardPageSupported = relay.getForwardPaginationConnectionFieldArguments().stream().allMatch(
                specArg -> arguments.stream().anyMatch(arg -> arg.getName().equals(specArg.getName())));
        boolean backwardPageSupported = relay.getBackwardPaginationConnectionFieldArguments().stream().allMatch(
                specArg -> arguments.stream().anyMatch(arg -> arg.getName().equals(specArg.getName())));

        if (!forwardPageSupported && !backwardPageSupported) {
            throw new MappingException(String.format(errorMessageTemplate, "required arguments missing"));
        }

        if (relay.getConnectionFieldArguments().stream().anyMatch(specArg -> arguments.stream().anyMatch(
                arg -> arg.getName().equals(specArg.getName())
                        && !GraphQLUtils.unwrap(arg.getType()).getName().equals(name(specArg.getType()))))) {
            throw new MappingException(String.format(errorMessageTemplate, "argument type mismatch"));
        }
    }

    /**
     * Fetches all the mapped GraphQL fields representing top-level queries, ready to be attached to the root query type.
     *
     * @return A list of GraphQL fields representing top-level queries
     */
    public List<GraphQLFieldDefinition> getQueries() {
        return queries;
    }

    /**
     * Fetches all the mapped GraphQL fields representing mutations, ready to be attached to the root mutation type.
     *
     * @return A list of GraphQL fields representing mutations
     */
    public List<GraphQLFieldDefinition> getMutations() {
        return mutations;
    }

    public List<GraphQLFieldDefinition> getSubscriptions() {
        return subscriptions;
    }

    public List<GraphQLDirective> getDirectives() {
        return directives;
    }
}
