package net.minestom.server.network.packet;

import net.minestom.server.ProcessOwned;
import net.minestom.server.ServerProcess;
import net.minestom.server.Viewable;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** Pending viewable packets, flushed only by their owning process. */
@ApiStatus.Internal
public final class PacketBatcher implements AutoCloseable {
    private final ServerProcess process;
    private final Map<Viewable, List<Entry>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PacketBatcher(ServerProcess process) {
        this.process = Objects.requireNonNull(process);
    }

    public void prepareViewablePacket(Viewable viewable, ServerPacket packet) {
        prepareViewablePacket(viewable, packet, null);
    }

    public void prepareViewablePacket(Viewable viewable, ServerPacket packet, @Nullable Entity entity) {
        if (closed) throw new RejectedExecutionException("Packet batcher is closed");
        if (entity != null) requireOwner(entity);
        if (viewable instanceof ProcessOwned target) requireOwner(target);
        pending.compute(viewable, (_, entries) -> {
            if (closed) throw new RejectedExecutionException("Packet batcher is closed");
            if (entries == null) entries = new ArrayList<>();
            entries.add(new Entry(packet, entity instanceof Player player ? player.getEntityId() : -1));
            return entries;
        });
        if (closed) pending.remove(viewable);
    }

    public void flush() {
        if (closed) return;
        pending.keySet().parallelStream().forEach(viewable -> {
            // Removal waits for this viewable's preparation; later packets get a new list.
            var entries = pending.remove(viewable);
            if (entries != null) flush(viewable, entries);
        });
    }

    private void flush(Viewable viewable, List<Entry> entries) {
        if (closed) return;
        var viewers = List.copyOf(viewable.getViewers());
        Map<PacketEncodingContext, List<Player>> recipients = new HashMap<>();
        for (Player player : viewers) {
            if (closed) return;
            if (player.process() != process) continue;
            var connection = player.getPlayerConnection();
            var context = connection.packetContext();
            if (context.state() != ConnectionState.PLAY) continue;
            if (connection instanceof PlayerSocketConnection) {
                recipients.computeIfAbsent(context, _ -> new ArrayList<>()).add(player);
            } else {
                for (Entry entry : entries) {
                    if (entry.excludedEntityId != player.getEntityId()) connection.sendPacket(entry.packet);
                }
            }
        }
        for (var group : recipients.entrySet()) {
            if (closed) return;
            var context = group.getKey();
            var buffer = NetworkBuffer.resizableBuffer(256, context.registries(), context.properties());
            long[] offsets = new long[entries.size() + 1];
            for (int i = 0; i < entries.size(); i++) {
                context.write(buffer, entries.get(i).packet);
                offsets[i + 1] = buffer.writeIndex();
            }
            for (Player player : group.getValue()) {
                long start = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).excludedEntityId != player.getEntityId()) continue;
                    sendRange(player, context, buffer, start, offsets[i]);
                    start = offsets[i + 1];
                }
                sendRange(player, context, buffer, start, buffer.writeIndex());
            }
        }
    }

    private static void sendRange(Player player, PacketEncodingContext context, NetworkBuffer buffer, long start, long end) {
        if (start != end) player.sendPacket(new BufferedPacket(context, buffer, start, end - start));
    }

    private void requireOwner(ProcessOwned owner) {
        if (owner.process() != process) throw new IllegalArgumentException("Foreign process in packet batch");
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
    }

    private record Entry(ServerPacket packet, int excludedEntityId) { }
}
