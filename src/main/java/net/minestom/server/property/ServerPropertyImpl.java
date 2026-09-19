package net.minestom.server.property;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

final class ServerPropertyImpl {

    static final String MUTABLE_SUFFIX = ".mutable";

    private static final String MUTABLE_DEFAULT = "minestom.properties" + MUTABLE_SUFFIX;

    private ServerPropertyImpl() {
    }

    private static boolean mutable(Properties source, String name) {
        final String raw = source.getProperty(name + MUTABLE_SUFFIX);
        if (raw != null) return Boolean.parseBoolean(raw);
        return Boolean.parseBoolean(source.getProperty(MUTABLE_DEFAULT, "false"));
    }

    static <T> ServerProperty<T> create(Properties source, String name, T defaultValue,
                                       Function<String, ? extends T> parser, @Nullable Consumer<? super T> validator,
                                       @Nullable T override, boolean allowWrites) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(parser, "parser");
        final T value = override != null ? override : resolve(source, name, defaultValue, parser);
        validate(validator, value);
        return allowWrites && mutable(source, name)
                ? new Mutable<>(name, defaultValue, validator, value)
                : new Immutable<>(name, defaultValue, value);
    }

    static <T> ServerProperty<T> copy(ServerProperty<T> property) {
        return switch (property) {
            case Immutable<T> immutable -> immutable;
            case Mutable<T> mutable -> new Mutable<>(mutable.name, mutable.defaultValue, mutable.validator, mutable.get());
        };
    }

    static <T> ServerProperty<T> create(String name, T defaultValue, Function<String, ? extends T> parser) {
        return create(System.getProperties(), name, defaultValue, parser, null, null, true);
    }

    static <T> ServerProperty<T> create(String name, T defaultValue,
                                       Function<String, ? extends T> parser, Consumer<? super T> validator) {
        return create(System.getProperties(), name, defaultValue, parser, Objects.requireNonNull(validator), null, true);
    }

    private static <T> T resolve(Properties source, String name, T defaultValue, Function<String, ? extends T> parser) {
        final String raw = source.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property '" + name + "' has an unparseable value: " + raw, e);
        }
    }

    private static <T> void validate(@Nullable Consumer<? super T> validator, T value) {
        if (validator != null) validator.accept(value);
    }

    /**
     * A property whose value was fixed when it was built. Its value is a record component, so reads
     * through a constant reference fold away to the value itself.
     *
     * @param name         the system property it was read from
     * @param defaultValue the value used when that system property was unset
     * @param value        the fixed value
     * @param <T>          the value type
     */
    record Immutable<T>(String name, T defaultValue, T value) implements ServerProperty<T> {

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T value) {
            throw new IllegalStateException("Property '" + name + "' is immutable; set -D" + name
                    + "=<value> at startup, or use ServerProperties.Builder for process-owned properties");
        }

        @Override
        public boolean writable() {
            return false;
        }

        @Override
        public String toString() {
            return name + "=" + value;
        }
    }

    /**
     * A property that still accepts writes.
     * Reads cannot fold, which is why properties are immutable unless asked otherwise.
     */
    static final class Mutable<T> implements ServerProperty<T> {
        private final String name;
        private final T defaultValue;
        private final @Nullable Consumer<? super T> validator;

        private volatile T value;

        Mutable(String name, T defaultValue, @Nullable Consumer<? super T> validator, T value) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.validator = validator;
            this.value = value;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public T defaultValue() {
            return defaultValue;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T value) {
            Objects.requireNonNull(value, "value");
            validate(validator, value);
            this.value = value;
        }

        @Override
        public boolean writable() {
            return true;
        }

        @Override
        public String toString() {
            return name + "=" + value;
        }
    }
}
