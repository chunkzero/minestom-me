package net.minestom.server.adventure.provider;

import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class MinestomLegacyComponentSerializerProvider implements LegacyComponentSerializer.Provider {
    @Override
    public LegacyComponentSerializer legacyAmpersand() {
        return LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.AMPERSAND_CHAR)
                .flattener(ComponentFlattener.basic())
                .build();
    }

    @Override
    public LegacyComponentSerializer legacySection() {
        return LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .flattener(ComponentFlattener.basic())
                .build();
    }

    @Override
    public Consumer<LegacyComponentSerializer.Builder> legacy() {
        return builder -> builder.flattener(ComponentFlattener.basic());
    }
}
