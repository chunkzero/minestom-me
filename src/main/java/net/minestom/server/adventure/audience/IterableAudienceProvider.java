package net.minestom.server.adventure.audience;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.minestom.server.ServerProcess;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * A provider of iterable audiences.
 */
class IterableAudienceProvider implements AudienceProvider<Iterable<? extends Audience>> {
    private final ServerProcess process;
    private final List<ConsoleSender> console;
    private final AudienceRegistry registry = new AudienceRegistry(new ConcurrentHashMap<>(), CopyOnWriteArrayList::new);

    protected IterableAudienceProvider(ServerProcess process) {
        this.process = process;
        this.console = List.of(process.commandManager().getConsoleSender());
    }

    @Override
    public Iterable<? extends Audience> all() {
        List<Audience> all = new ArrayList<>();
        this.players().forEach(all::add);
        this.console().forEach(all::add);
        this.customs().forEach(all::add);
        return all;
    }

    @Override
    public Iterable<? extends Audience> players() {
        return process.connectionManager().getOnlinePlayers();
    }

    @Override
    public Iterable<? extends Audience> players(Predicate<? super Player> filter) {
        return process.connectionManager().getOnlinePlayers().stream().filter(filter).toList();
    }

    @Override
    public Iterable<? extends Audience> console() {
        return this.console;
    }

    @Override
    public Iterable<? extends Audience> server() {
        List<Audience> all = new ArrayList<>();
        this.players().forEach(all::add);
        this.console().forEach(all::add);
        return all;
    }

    @Override
    public Iterable<? extends Audience> customs() {
        return this.registry.all();
    }

    @Override
    public Iterable<? extends Audience> custom(Key key) {
        return this.registry.of(key);
    }

    @Override
    public Iterable<? extends Audience> custom(Key key, Predicate<? super Audience> filter) {
        return StreamSupport.stream(this.registry.of(key).spliterator(), false).filter(filter).toList();
    }

    @Override
    public Iterable<? extends Audience> customs(Predicate<? super Audience> filter) {
        return this.registry.of(filter);
    }

    @Override
    public Iterable<? extends Audience> all(Predicate<? super Audience> filter) {
        return StreamSupport.stream(this.all().spliterator(), false).filter(filter).toList();
    }

    @Override
    public AudienceRegistry registry() {
        return this.registry;
    }
}
