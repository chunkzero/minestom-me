package net.minestom.server.adventure;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.translation.GlobalTranslator;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;

/** Translation settings belonging to one process. Global Adventure serializers have no process context. */
public final class ComponentTranslation {
    private volatile Locale defaultLocale = Locale.getDefault();
    private volatile BiFunction<Component, Locale, Component> translator = GlobalTranslator::render;

    public Locale defaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(Locale locale) {
        this.defaultLocale = Objects.requireNonNull(locale);
    }

    public void setTranslator(BiFunction<Component, Locale, Component> translator) {
        this.translator = Objects.requireNonNull(translator);
    }

    public Component translate(Component component, @Nullable Locale locale) {
        return translator.apply(component, locale != null ? locale : defaultLocale);
    }

    /** Use with an Adventure serializer builder when text must be flattened in this process's context. */
    public ComponentFlattener flattener() {
        return ComponentFlattener.basic().toBuilder()
                .complexMapper(TranslatableComponent.class, (component, consumer) -> {
                    var translated = translate(component, null);
                    consumer.accept(translated instanceof TranslatableComponent unresolved
                            ? Component.text(Objects.requireNonNullElse(unresolved.fallback(), unresolved.key())) : translated);
                }).build();
    }
}
