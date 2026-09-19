package net.minestom.server.property;

import java.util.function.Supplier;

/**
 * A named server setting, initialized from the system property it is named after.
 *
 * <p>A property starts at its default value, the parsed system property named {@link #name()}, or
 * an explicit construction override. {@link #get()} returns the current value.</p>
 *
 * <p>A property is immutable unless {@code <name>.mutable} says otherwise, defaulting to the
 * {@code minestom.properties.mutable} system property. An immutable property fixes its value when it
 * is built and throws {@link IllegalStateException} from {@link #set(Object)}. Process startup
 * settings and serialization limits are always immutable, regardless of the mutable flags.
 * Use {@link ServerProperties.Builder} to override initial values without requiring runtime writes.</p>
 *
 * <p>A written value is visible to other threads, but a write racing a read may or may not be seen.</p>
 *
 * @param <T> the value type
 */
public sealed interface ServerProperty<T> extends Supplier<T> permits ServerPropertyImpl.Immutable, ServerPropertyImpl.Mutable {

    /**
     * Gets the system property this property reads at startup, which also names it in error messages.
     *
     * @return the system property name
     */
    String name();

    /**
     * Gets the value this property falls back to when its system property is unset.
     *
     * @return the default value
     */
    T defaultValue();

    /**
     * Gets the current value.
     *
     * @return the current value
     */
    @Override
    T get();

    /**
     * Replaces the current value.
     *
     * @param value the new value
     * @throws IllegalStateException    if the property is immutable
     * @throws IllegalArgumentException if the value is outside the accepted range
     */
    void set(T value);

    /**
     * Reports whether {@link #set(Object)} would currently be accepted.
     *
     * @return true if the property still accepts writes
     */
    boolean writable();

}
