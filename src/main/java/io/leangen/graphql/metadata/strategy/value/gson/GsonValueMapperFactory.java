package io.leangen.graphql.metadata.strategy.value.gson;

import com.google.gson.FieldNamingStrategy;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapterFactory;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.graphql.execution.GlobalEnvironment;
import io.leangen.graphql.metadata.strategy.type.DefaultTypeInfoGenerator;
import io.leangen.graphql.metadata.strategy.type.TypeInfoGenerator;
import io.leangen.graphql.metadata.strategy.value.ScalarDeserializationStrategy;
import io.leangen.graphql.metadata.strategy.value.ValueMapper;
import io.leangen.graphql.metadata.strategy.value.ValueMapperFactory;
import io.leangen.graphql.util.ClassUtils;
import net.dongliu.gson.GsonJava8TypeAdapterFactory;

import java.lang.reflect.AnnotatedType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Bojan Tomic (kaqqao)
 */
public class GsonValueMapperFactory implements ValueMapperFactory<GsonValueMapper>, ScalarDeserializationStrategy {

    private final FieldNamingStrategy fieldNamingStrategy;
    private final TypeInfoGenerator typeInfoGenerator;
    private final Configurer configurer;

    @SuppressWarnings("WeakerAccess")
    public GsonValueMapperFactory() {
        this(new DefaultTypeInfoGenerator(), new GsonFieldNamingStrategy(), new AbstractClassAdapterConfigurer());
    }

    private GsonValueMapperFactory(TypeInfoGenerator typeInfoGenerator, FieldNamingStrategy fieldNamingStrategy, Configurer configurer) {
        this.fieldNamingStrategy = Objects.requireNonNull(fieldNamingStrategy);
        this.typeInfoGenerator = Objects.requireNonNull(typeInfoGenerator);
        this.configurer = Objects.requireNonNull(configurer);
    }

    @Override
    public GsonValueMapper getValueMapper(Map<Class, List<Class>> concreteSubTypes, GlobalEnvironment environment) {
        return new GsonValueMapper(initBuilder(concreteSubTypes, environment).create());
    }

    public static Builder builder() {
        return new Builder();
    }

    private GsonBuilder initBuilder(Map<Class, List<Class>> concreteSubTypes, GlobalEnvironment environment) {
        GsonBuilder gsonBuilder = new GsonBuilder()
                .setFieldNamingStrategy(fieldNamingStrategy)
                .registerTypeAdapterFactory(new GsonJava8TypeAdapterFactory());
        return configurer.configure(gsonBuilder, concreteSubTypes, this.typeInfoGenerator, environment);
    }

    @Override
    public boolean isDirectlyDeserializable(AnnotatedType type) {
        return GenericTypeReflector.isSuperType(JsonElement.class, type.getType());
    }

    public static class AbstractClassAdapterConfigurer implements Configurer {

        @Override
        public GsonBuilder configure(GsonBuilder gsonBuilder, Map<Class, List<Class>> concreteSubTypes, TypeInfoGenerator infoGenerator, GlobalEnvironment environment) {
            concreteSubTypes.entrySet().stream()
                    .map(entry -> adapterFor(entry.getKey(), entry.getValue(), infoGenerator))
                    .filter(Objects::nonNull)
                    .forEach(gsonBuilder::registerTypeAdapterFactory);

            if (environment != null && !environment.getInputConverters().isEmpty()) {
                gsonBuilder.registerTypeAdapterFactory(new ConvertingAdapterFactory(environment));
            }

            return gsonBuilder;
        }

        @SuppressWarnings("unchecked")
        private TypeAdapterFactory adapterFor(Class superClass, List<Class> implementations, TypeInfoGenerator infoGen) {
            RuntimeTypeAdapterFactory adapterFactory = RuntimeTypeAdapterFactory.of(superClass, ValueMapper.TYPE_METADATA_FIELD_NAME);
            if (implementations.isEmpty()) {
                return null;
            }
            implementations.stream()
                    .filter(impl -> !ClassUtils.isAbstract(impl))
                    .forEach(impl -> adapterFactory.registerSubtype(impl, infoGen.generateTypeName(GenericTypeReflector.annotate(impl))));

            return adapterFactory;
        }
    }

    @FunctionalInterface
    public interface Configurer {
        GsonBuilder configure(GsonBuilder gsonBuilder, Map<Class, List<Class>> concreteSubTypes, TypeInfoGenerator infoGenerator, GlobalEnvironment environment);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " with " + typeInfoGenerator.getClass().getSimpleName();
    }

    public static class Builder {

        private FieldNamingStrategy fieldNamingStrategy = new GsonFieldNamingStrategy();
        private TypeInfoGenerator typeInfoGenerator = new DefaultTypeInfoGenerator();
        private Configurer configurer = new AbstractClassAdapterConfigurer();

        public Builder withFieldNamingStrategy(FieldNamingStrategy fieldNamingStrategy) {
            this.fieldNamingStrategy = fieldNamingStrategy;
            return this;
        }

        public Builder withTypeInfoGenerator(TypeInfoGenerator typeInfoGenerator) {
            this.typeInfoGenerator = typeInfoGenerator;
            return this;
        }

        public Builder withConfigurer(Configurer configurer) {
            this.configurer = configurer;
            return this;
        }

        public GsonValueMapperFactory build() {
            return new GsonValueMapperFactory(typeInfoGenerator, fieldNamingStrategy, configurer);
        }
    }
}
