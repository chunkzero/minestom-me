package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Objects;
import java.util.function.UnaryOperator;

final class TagAccessors {
    static class Readable implements TagReadable {
        private final TagReadable readable;
        private final Registries registries;

        Readable(TagReadable readable, Registries registries) {
            this.readable = readable;
            this.registries = Objects.requireNonNull(registries);
        }

        @Override
        public Registries tagRegistries() {
            return registries;
        }

        @Override
        public TagReadable withRegistries(Registries registries) {
            return readable.withRegistries(registries);
        }

        @Override
        public <T> @UnknownNullability T getTag(ContextualTag<T> tag, Registries registries) {
            return readable.getTag(tag, registries);
        }

        @Override
        public <T> @UnknownNullability T getTag(Tag<T> tag) {
            return readable.getTag(tag);
        }
    }

    static class Writable extends Readable implements TagWritable {
        private final TagWritable writable;

        Writable(TagWritable writable, Registries registries) {
            super(writable, registries);
            this.writable = writable;
        }

        @Override
        public TagWritable withRegistries(Registries registries) {
            return writable.withRegistries(registries);
        }

        @Override
        public <T> void setTag(Tag<T> tag, @Nullable T value) {
            writable.setTag(tag, value);
        }

        @Override
        public <T> @Nullable T getAndSetTag(Tag<T> tag, @Nullable T value) {
            return writable.getAndSetTag(tag, value);
        }

        @Override
        public <T> void updateTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
            writable.updateTag(tag, value);
        }

        @Override
        public <T> @UnknownNullability T updateAndGetTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
            return writable.updateAndGetTag(tag, value);
        }

        @Override
        public <T> @UnknownNullability T getAndUpdateTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
            return writable.getAndUpdateTag(tag, value);
        }
    }

    static final class Handler extends Writable implements TagHandler {
        private final TagHandler handler;

        Handler(TagHandler handler, Registries registries) {
            super(handler, registries);
            this.handler = handler;
        }

        @Override
        public TagHandler withRegistries(Registries registries) {
            return handler.withRegistries(registries);
        }

        @Override
        public TagReadable readableCopy() {
            return handler.readableCopy().withRegistries(tagRegistries());
        }

        @Override
        public TagHandler copy() {
            return handler.copy().withRegistries(tagRegistries());
        }

        @Override
        public void updateContent(CompoundBinaryTag compound) {
            handler.updateContent(compound);
        }

        @Override
        public CompoundBinaryTag asCompound() {
            return handler.asCompound();
        }
    }
}
