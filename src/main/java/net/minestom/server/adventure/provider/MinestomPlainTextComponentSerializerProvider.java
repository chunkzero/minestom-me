package net.minestom.server.adventure.provider;

import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class MinestomPlainTextComponentSerializerProvider implements PlainTextComponentSerializer.Provider {
    @Override
    public PlainTextComponentSerializer plainTextSimple() {
        return PlainTextComponentSerializer.builder()
                .flattener(ComponentFlattener.basic())
                .build();
    }

    @Override
    public Consumer<PlainTextComponentSerializer.Builder> plainText() {
        return builder -> builder.flattener(ComponentFlattener.basic());
    }
}
