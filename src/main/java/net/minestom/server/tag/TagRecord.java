package net.minestom.server.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.item.ItemStack;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static java.util.Map.entry;

final class TagRecord {
    private static final Map<Class<?>, Function<RecordComponent, Field>> ORDINARY_TYPES = Map.ofEntries(
            entry(Byte.class, ordinary(Byte.class, Tag::Byte)), entry(byte.class, ordinary(Byte.class, Tag::Byte)),
            entry(Boolean.class, ordinary(Boolean.class, Tag::Boolean)), entry(boolean.class, ordinary(Boolean.class, Tag::Boolean)),
            entry(Short.class, ordinary(Short.class, Tag::Short)), entry(short.class, ordinary(Short.class, Tag::Short)),
            entry(Integer.class, ordinary(Integer.class, Tag::Integer)), entry(int.class, ordinary(Integer.class, Tag::Integer)),
            entry(Long.class, ordinary(Long.class, Tag::Long)), entry(long.class, ordinary(Long.class, Tag::Long)),
            entry(Float.class, ordinary(Float.class, Tag::Float)), entry(float.class, ordinary(Float.class, Tag::Float)),
            entry(Double.class, ordinary(Double.class, Tag::Double)), entry(double.class, ordinary(Double.class, Tag::Double)),
            entry(String.class, ordinary(String.class, Tag::String)),
            entry(UUID.class, ordinary(UUID.class, Tag::UUID)));

    private static final Map<Class<?>, Function<RecordComponent, Field>> CONTEXTUAL_TYPES = Map.of(
            ItemStack.class, contextual(ItemStack.class, Tag::ItemStack),
            Component.class, contextual(Component.class, Tag::Component));

    // Only reflection metadata and reusable definitions are cached; no registries or converted values.
    private static final ClassValue<Serializer<?>> SERIALIZERS = new ClassValue<>() {
        @Override
        protected Serializer<?> computeValue(Class<?> type) {
            return new Serializer<>(type.asSubclass(Record.class));
        }
    };

    private static final ClassValue<ContextualSerializer<?>> CONTEXTUAL_SERIALIZERS = new ClassValue<>() {
        @Override
        protected ContextualSerializer<?> computeValue(Class<?> type) {
            return new ContextualSerializer<>(type.asSubclass(Record.class));
        }
    };

    @SuppressWarnings("unchecked") // ClassValue is keyed by exactly the serializer's record class.
    static <T extends Record> Serializer<T> serializer(Class<T> type) {
        validate(type, false, new HashSet<>());
        return (Serializer<T>) SERIALIZERS.get(type);
    }

    @SuppressWarnings("unchecked") // ClassValue is keyed by exactly the serializer's record class.
    static <T extends Record> ContextualSerializer<T> contextualSerializer(Class<T> type) {
        validate(type, true, new HashSet<>());
        return (ContextualSerializer<T>) CONTEXTUAL_SERIALIZERS.get(type);
    }

    private static void validate(Class<?> type, boolean contextual, Set<Class<?>> visiting) {
        if (!type.isRecord()) throw new IllegalArgumentException("Expected a record: " + type);
        if (!visiting.add(type)) throw new IllegalArgumentException("Recursive record tags are unsupported: " + type);
        for (var component : type.getRecordComponents()) {
            var fieldType = component.getType();
            if (CONTEXTUAL_TYPES.containsKey(fieldType)) {
                if (!contextual) throw new IllegalArgumentException("Contextual field " + type.getName() + "." + component.getName()
                        + " requires ContextualTag.Structure or ContextualTag.View");
            } else if (fieldType.isRecord()) {
                validate(fieldType, contextual, visiting);
            } else if (!ORDINARY_TYPES.containsKey(fieldType) && !BinaryTag.class.isAssignableFrom(fieldType)) {
                throw new IllegalArgumentException("Unsupported type: " + fieldType);
            }
        }
        visiting.remove(type);
    }

    private static Field[] fields(Class<? extends Record> type, boolean contextual) {
        return Arrays.stream(type.getRecordComponents()).map(component -> {
            Class<?> fieldType = component.getType();
            var factory = ORDINARY_TYPES.get(fieldType);
            if (factory != null) return factory.apply(component);
            if (BinaryTag.class.isAssignableFrom(fieldType))
                return ordinary(BinaryTag.class, Tag::NBT).apply(component);
            if (fieldType.isRecord()) return recordField(component, fieldType.asSubclass(Record.class), contextual);
            return CONTEXTUAL_TYPES.get(fieldType).apply(component);
        }).toArray(Field[]::new);
    }

    private static <T extends Record> Field recordField(RecordComponent component, Class<T> type, boolean contextual) {
        return contextual
                ? new ContextualField<>(component, type, ContextualTag.Structure(component.getName(), type))
                : new OrdinaryField<>(component, type, Tag.Structure(component.getName(), type));
    }

    private static <T> Function<RecordComponent, Field> ordinary(Class<T> type, Function<String, Tag<T>> factory) {
        return component -> new OrdinaryField<>(component, type, factory.apply(component.getName()));
    }

    private static <T> Function<RecordComponent, Field> contextual(Class<T> type, Function<String, ContextualTag<T>> factory) {
        return component -> new ContextualField<>(component, type, factory.apply(component.getName()));
    }

    private static <T extends Record> Constructor<T> constructor(Class<T> type) {
        try {
            return type.getDeclaredConstructor(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class[]::new));
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static <T extends Record> @Nullable T read(Constructor<T> constructor, Field[] fields, TagReadable reader) {
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].read(reader);
            if (values[i] == null) return null;
        }
        try {
            return constructor.newInstance(values);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Field[] fields, TagWritable writer, Record value) {
        try {
            for (Field field : fields) field.write(writer, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    static final class Serializer<T extends Record> implements TagSerializer<T> {
        private final Constructor<T> constructor;
        private final Field[] fields;
        final Serializers.Entry<T, CompoundBinaryTag> serializerEntry;

        Serializer(Class<T> type) {
            this.constructor = constructor(type);
            this.fields = fields(type, false);
            this.serializerEntry = Serializers.fromTagSerializer(this);
        }

        @Override
        public @Nullable T read(TagReadable reader) {
            return TagRecord.read(constructor, fields, reader);
        }

        @Override
        public void write(TagWritable writer, T value) {
            TagRecord.write(fields, writer, value);
        }
    }

    static final class ContextualSerializer<T extends Record> implements ContextualTagSerializer<T> {
        private final Constructor<T> constructor;
        private final Field[] fields;

        ContextualSerializer(Class<T> type) {
            this.constructor = constructor(type);
            this.fields = fields(type, true);
        }

        @Override
        public @Nullable T read(TagReadable reader, Registries registries) {
            return TagRecord.read(constructor, fields, reader.withRegistries(registries));
        }

        @Override
        public void write(TagWritable writer, T value, Registries registries) {
            TagRecord.write(fields, writer.withRegistries(registries), value);
        }
    }

    private interface Field {
        @Nullable Object read(TagReadable reader);

        void write(TagWritable writer, Record value) throws IllegalAccessException, InvocationTargetException;
    }

    private record OrdinaryField<T>(RecordComponent component, Class<T> type, Tag<T> tag) implements Field {
        @Override
        public @Nullable Object read(TagReadable reader) {
            return reader.getTag(tag);
        }

        @Override
        public void write(TagWritable writer, Record value) throws IllegalAccessException, InvocationTargetException {
            writer.setTag(tag, type.cast(component.getAccessor().invoke(value)));
        }
    }

    private record ContextualField<T>(RecordComponent component, Class<T> type, ContextualTag<T> tag) implements Field {
        @Override
        public @Nullable Object read(TagReadable reader) {
            return reader.getTag(tag);
        }

        @Override
        public void write(TagWritable writer, Record value) throws IllegalAccessException, InvocationTargetException {
            writer.setTag(tag, type.cast(component.getAccessor().invoke(value)));
        }
    }
}
