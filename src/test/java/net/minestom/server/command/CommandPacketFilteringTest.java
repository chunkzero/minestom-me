package net.minestom.server.command;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.DeclareCommandsPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.UUID;

public class CommandPacketFilteringTest {
    @Test
    public void singleCommandFilteredFalse() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> false);
        assertFiltering(foo, "");
    }

    @Test
    public void singleCommandFilteredTrue() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> true);
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void singleCommandUnfiltered() {
        final Command foo = new Command("foo");
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void singleCommandFilteredTrueWithFilteredSubcommandTrueWithFilteredSyntaxFalse() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> true);
        final Command bar = new Command("bar");
        bar.setCondition((_, _) -> true);
        foo.addSubcommand(bar);
        bar.addConditionalSyntax((_, _) -> false, null, ArgumentType.Literal("baz"));
        assertFiltering(foo, """
                foo bar=%
                0->foo
                foo->bar
                """);
    }

    @Test
    public void singleCommandFilteredTrueWithFilteredSubcommandFalse() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> true);
        final Command bar = new Command("bar");
        bar.setCondition((_, _) -> false);
        foo.addSubcommand(bar);
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void singleCommandFilteredTrueWithFilteredSubcommandTrue() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> true);
        final Command bar = new Command("bar");
        bar.setCondition((_, _) -> true);
        foo.addSubcommand(bar);
        assertFiltering(foo, """
                foo bar=%
                0->foo
                foo->bar
                """);
    }

    @Test
    public void singleCommandFilteredTrueWithFilteredSubcommandTrueWithFilteredSyntaxBoth() {
        final Command foo = new Command("foo");
        foo.setCondition((_, _) -> true);
        final Command bar = new Command("bar");
        bar.setCondition((_, _) -> true);
        foo.addSubcommand(bar);
        bar.addConditionalSyntax((_, _) -> true, null, ArgumentType.Literal("true"));
        bar.addConditionalSyntax((_, _) -> false, null, ArgumentType.Literal("false"));
        assertFiltering(foo, """
                foo bar true=%
                0->foo
                foo->bar
                bar->true
                """);
    }

    @Test
    public void singleCommandConditionalArgGroupTrue() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> true, null, ArgumentType.Group("test", ArgumentType.Literal("bar")));
        assertFiltering(foo, """
                foo bar=%
                0->foo
                foo->bar
                """);
    }

    @Test
    public void singleCommandConditionalArgGroupFalse() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> false, null, ArgumentType.Group("test", ArgumentType.Literal("foo")));
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void singleCommandUnconditionalArgGroup() {
        final Command foo = new Command("foo");
        foo.addSyntax(null, ArgumentType.Group("test", ArgumentType.Literal("bar")));
        assertFiltering(foo, """
                foo bar=%
                0->foo
                foo->bar
                """);
    }

    @Test
    public void singleCommandConditionalArgGroupTrue2() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> true, null, ArgumentType.Group("test", ArgumentType.Literal("bar"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo bar baz=%
                0->foo
                foo->bar
                bar->baz
                """);
    }

    @Test
    public void singleCommandConditionalArgGroupFalse2() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> false, null, ArgumentType.Group("test", ArgumentType.Literal("foo"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    @Test
    public void singleCommandUnconditionalArgGroup2() {
        final Command foo = new Command("foo");
        foo.addSyntax(null, ArgumentType.Group("test", ArgumentType.Literal("bar"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo bar baz=%
                0->foo
                foo->bar
                bar->baz
                """);
    }

    @Test
    public void singleCommandUnconditionalArgLoop() {
        final Command foo = new Command("foo");
        foo.addSyntax(null, ArgumentType.Loop("test", ArgumentType.Literal("bar"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo bar baz=%
                0->foo
                foo->bar baz
                bar baz+>foo
                """);
    }

    @Test
    public void singleCommandConditionalArgLoopTrue() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> true, null, ArgumentType.Loop("test", ArgumentType.Literal("bar"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo bar baz=%
                0->foo
                foo->bar baz
                bar baz+>foo
                """);
    }

    @Test
    public void singleCommandConditionalArgLoopFalse() {
        final Command foo = new Command("foo");
        foo.addConditionalSyntax((_, _) -> false, null, ArgumentType.Loop("test", ArgumentType.Literal("bar"), ArgumentType.Literal("baz")));
        assertFiltering(foo, """
                foo=%
                0->foo
                """);
    }

    private static void assertFiltering(Command command, String expectedStructure) {
        try (var process = ServerProcess.create()) {
            var connection = new PlayerConnection(process) {
                @Override public void sendPacket(SendablePacket packet) { }
                @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
            };
            var player = new Player(connection, new GameProfile(UUID.randomUUID(), "Test"));
            final DeclareCommandsPacket packet = GraphConverter.createPacket(process.command(), Graph.merge(Set.of(command)), player);
            CommandTestUtils.assertPacket(packet, expectedStructure);
        }
    }
}
