package net.minestom.server.adventure.provider;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class MinestomClickCallbackProvider implements ClickCallback.Provider {
    @Override
    public ClickEvent<ClickEvent.Payload.Custom> create(ClickCallback<Audience> callback, ClickCallback.Options options) {
        throw new UnsupportedOperationException("Use process.clickCallbackManager().createClickEvent(callback, options)");
    }
}
