package net.minestom.testing;

import net.minestom.server.Auth;
import net.minestom.server.ServerProcess;

/** Two unstarted processes that leave the default process untouched. */
public final class ServerProcessPair implements AutoCloseable {
    private final ServerProcess first;
    private final ServerProcess second;

    public ServerProcessPair() {
        this(new Auth.Offline(), new Auth.Offline());
    }

    public ServerProcessPair(Auth firstAuth, Auth secondAuth) {
        first = ServerProcess.create(firstAuth);
        try {
            second = ServerProcess.create(secondAuth);
        } catch (RuntimeException | Error error) {
            first.close();
            throw error;
        }
    }

    public ServerProcess first() {
        return first;
    }

    public ServerProcess second() {
        return second;
    }

    @Override
    public void close() {
        try {
            second.close();
        } finally {
            first.close();
        }
    }
}
