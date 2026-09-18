package net.minestom.server.adventure.audience;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Player;

import java.util.function.Predicate;

/**
 * Audiences and custom audience registrations belonging to one server process.
 */
public class Audiences {
    private final SingleAudienceProvider audience;

    public Audiences(ServerProcess process) {
        this.audience = new SingleAudienceProvider(process);
    }

    /**
     * Gets the {@link AudienceProvider} that provides forwarding audiences.
     *
     * @return the instance
     */
    public AudienceProvider<Audience> single() {
        return audience;
    }

    /**
     * Gets the {@link AudienceProvider} that provides iterables of audience members.
     *
     * @return the instance
     */
    public AudienceProvider<Iterable<? extends Audience>> iterable() {
        return audience.collection;
    }

    /**
     * Gets all audience members. This returns {@link #players()} combined with
     * {@link #customs()} and {@link #console()}. This can be a costly operation, so it
     * is often preferable to use {@link #server()} instead.
     *
     * @return all audience members
     */
    public Audience all() {
        return Audience.audience(audience.server, audience.customs());
    }

    /**
     * Gets all audience members that are of type {@link Player}.
     *
     * @return all players
     */
    public Audience players() {
        return audience.players;
    }

    /**
     * Gets all audience members that are of type {@link Player} and match the predicate.
     *
     * @param filter the predicate
     * @return all players matching the predicate
     */
    public Audience players(Predicate<? super Player> filter) {
        return audience.players(filter);
    }

    /**
     * Gets the console as an audience.
     *
     * @return the console
     */
    public Audience console() {
        return audience.console();
    }

    /**
     * Gets the combination of {@link #players()} and {@link #console()}.
     *
     * @return the audience of all players and the console
     */
    public Audience server() {
        return audience.server;
    }

    /**
     * Gets all custom audience members.
     *
     * @return all custom audience members
     */
    public Audience customs() {
        return Audience.audience(audience.iterable().customs());
    }

    /**
     * Gets all custom audience members stored using the given keyed object.
     *
     * @param keyed the keyed object
     * @return all custom audience members stored using the key of the object
     */
    public Audience custom(Keyed keyed) {
        return custom(keyed.key());
    }

    /**
     * Gets all custom audience members stored using the given key.
     *
     * @param key the key
     * @return all custom audience members stored using the key
     */
    public Audience custom(Key key) {
        return Audience.audience(audience.iterable().custom(key));
    }

    /**
     * Gets all custom audience members stored using the given keyed object that match
     * the given predicate.
     *
     * @param keyed  the keyed object
     * @param filter the predicate
     * @return all custom audience members stored using the key
     */
    public Audience custom(Keyed keyed, Predicate<? super Audience> filter) {
        return custom(keyed.key(), filter);
    }

    /**
     * Gets all custom audience members stored using the given key that match the
     * given predicate.
     *
     * @param key    the key
     * @param filter the predicate
     * @return all custom audience members stored using the key
     */
    public Audience custom(Key key, Predicate<? super Audience> filter) {
        return Audience.audience(audience.iterable().custom(key, filter));
    }

    /**
     * Gets all custom audience members matching the given predicate.
     *
     * @param filter the predicate
     * @return all matching custom audience members
     */
    public Audience customs(Predicate<? super Audience> filter) {
        return Audience.audience(audience.iterable().customs(filter));
    }

    /**
     * Gets all audience members that match the given predicate.
     *
     * @param filter the predicate
     * @return all matching audience members
     */
    public Audience all(Predicate<? super Audience> filter) {
        return Audience.audience(audience.iterable().all(filter));
    }

    /**
     * Gets the audience registry used to register custom audiences.
     *
     * @return the registry
     */
    public AudienceRegistry registry() {
        return audience.iterable().registry();
    }
}
