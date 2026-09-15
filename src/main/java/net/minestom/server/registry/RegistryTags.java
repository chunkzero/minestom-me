package net.minestom.server.registry;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable tags and their revision, independent of the registry's entry storage. */
final class RegistryTags<T> {
    private final Map<TagKey<T>, RegistryTagImpl.Backed<T>> tags = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    @Nullable RegistryTagImpl.Backed<T> get(TagKey<T> key) {
        return tags.get(key);
    }

    RegistryTagImpl.Backed<T> getOrCreate(TagKey<T> key) {
        var existing = tags.get(key);
        if (existing != null) return existing;
        var created = new RegistryTagImpl.Backed<>(key, Collections.emptyList(), revision::incrementAndGet);
        existing = tags.putIfAbsent(key, created);
        if (existing != null) return existing;
        revision.incrementAndGet();
        return created;
    }

    boolean remove(TagKey<T> key) {
        if (tags.remove(key) == null) return false;
        revision.incrementAndGet();
        return true;
    }

    Collection<RegistryTagImpl.Backed<T>> values() {
        return tags.values();
    }

    int size() {
        return tags.size();
    }

    long revision() {
        return revision.get();
    }

    void invalidate() {
        revision.incrementAndGet();
    }

    void load(Collection<RegistryTagImpl.Backed<T>> source) {
        for (var tag : source) {
            tags.put(tag.key(), new RegistryTagImpl.Backed<>(tag.key(), tag, revision::incrementAndGet));
        }
        revision.incrementAndGet();
    }

    RegistryTags<T> copy() {
        var copy = new RegistryTags<T>();
        copy.load(tags.values());
        return copy;
    }
}
