# API differences from upstream Minestom

The main premise of this fork is to be able to run multiple Minestom servers within one JVM.

- [Startup and lifecycle](#startup-and-lifecycle)
- [Services and settings](#services-and-settings)
- [Instances, entities, and gameplay](#instances-entities-and-gameplay)
- [Events](#events)
- [Commands](#commands)
- [Scheduling](#scheduling)
- [Tags and serialization](#tags-and-serialization)
- [Registries and predicates](#registries-and-predicates)
- [Adventure and audiences](#adventure-and-audiences)
- [Networking, ping, and LAN](#networking-ping-and-lan)

## Startup and lifecycle

| Upstream | This fork |
| --- | --- |
| `MinecraftServer.init(auth)` | `ServerProcess.create(auth)`; `create()` defaults to offline authentication. |
| `server.start(host, port)` | `process.start(new InetSocketAddress(host, port))`. Configure and register content before starting. |
| `MinecraftServer.stopCleanly()` | `process.close()` or `process.stop()`. Other processes keep running. |
| `MinecraftServer.isStarted()` | `process.isAlive()`. |

Closing is idempotent. Failed startup closes that process, and a new one should be created to retry. Shutdown continues through callback failures, cancels pending replies, and releases owned resources. Schedulers close before shutdown callbacks and player disconnection, so teardown must perform cleanup directly. A JVM shutdown hook is installed at startup and removed on close.

## Services and settings

| Old getter | Replacement | Old getter | Replacement |
| --- | --- | --- | --- |
| `getInstanceManager()` | `process.instanceManager()` | `getConnectionManager()` | `process.connectionManager()` |
| `getCommandManager()` | `process.commandManager()` | `getBlockManager()` | `process.blockManager()` |
| `getRecipeManager()` | `process.recipeManager()` | `getTeamManager()` | `process.teamManager()` |
| `getAdvancementManager()` | `process.advancementManager()` | `getBossBarManager()` | `process.bossBarManager()` |
| `getSchedulerManager()` | `process.schedulerManager()` | `getExceptionManager()` | `process.exceptionManager()` |
| `getGlobalEventHandler()` | `process.eventHandler()` | `getPacketListenerManager()` | `process.packetListenerManager()` |
| `getPacketParser()` | `process.packetParser()` | `getClickCallbackManager()` | `process.clickCallbackManager()` |
| `getRegistries()` | `process.registries()` | `getServer()` | `process.server()` |

| Area | Current behavior |
| --- | --- |
| Registry access | `ServerProcess` no longer implements `Registries`. Use `process.registries().biome()`, `.dimensionType()`, etc. |
| Brand and difficulty | `process.setBrandName(...)` / `.brandName()` and `.setDifficulty(...)` / `.difficulty()`. |
| Compression | `process.setCompressionThreshold(...)` before startup; zero disables compression. Connections retain their negotiated setting. |
| Version constants | `MinecraftConstants.VERSION_NAME`, `PROTOCOL_VERSION`, `DATA_VERSION`, and resource/data-pack versions. |
| Former facade constants | Read the corresponding `ServerProperties` value directly, e.g. `ServerProperties.CHUNK_VIEW_DISTANCE.get()`. |
| Shared configuration | `ServerProperties`, including tick rate and view distances, remain JVM-wide. This will likely be changed in the near future. |
| Diagnostics | `process.id()` distinguishes processes within a JVM. |

## Instances, entities, and gameplay

| Operation | Migration / semantics |
| --- | --- |
| Create instances | Prefer `process.instanceManager().createInstanceContainer()`. Direct `InstanceContainer` constructors take `process` first. Shared instances and copies derive it from their source. |
| Load/save worlds | `AnvilLoader` construction is unchanged. Load/save operations obtain registries and exception handling from the instance/chunk. |
| Create entities | `new Entity(process, type)`; `LivingEntity` and `EntityCreature` also require an owner. Custom subclasses pass it to `super`. |
| Items and orbs | `new ItemEntity(process, item)` and `new ExperienceOrb(process, count)`. |
| Projectiles | `new EntityProjectile(shooter, type)` derives ownership; use the explicit-process constructor when the shooter is absent. |
| Players | `Player(connection, profile)` keeps its signature and derives ownership from the connection. |
| Builders | `Entity.builder(type).spawn(instance, position)` uses the process from the instance, and returns a future, like Entity#setInstance. |
| Builder ordering | Listeners are installed before settings and initializers. Initializers must not place/remove the entity; failed creation or placement cleans it up. Each spawn creates a fresh entity. |
| Entity IDs | `process.generateEntityId()` replaces `Entity.generateId()`. IDs can overlap across processes. External state should be indexed by the process and ID together. |
| Moving entities | Moving between instances of one process works. Foreign-process placement, viewers, passengers, leashes, and related owned references are rejected. Client transfer creates a new connection/player at the destination. |
| Inventories | `new Inventory(process, type, title)`; typed inventories and direct `PlayerInventory` construction also take an owner. `player.getInventory()`, item mutation, and opening APIs stay familiar. Foreign viewers are rejected. |
| Teams | Create through `process.teamManager()`. `exists(team)` checks its name in the owning manager; registration rejects a different object with that name. Rebuilding the same registered team does not resend creation packets. |
| Recipes, advancements, boss bars | Use the process's managers. Advancement membership remains UUID-to-tab-set, scoped to that manager. Normal player boss-bar and sidebar APIs stay the same. |
| Damage | Direct `Damage` / `PositionalDamage` construction and `Damage.fromPosition(...)` take a process. Entity/projectile factories infer it; `livingEntity.damage(...)` supplies its owner. Will probably be simplified in the future. |

Entity builders are under `EntityBuilder<T, B>`, `LivingEntity`, `EntityCreature`, `EntityProjectile`, `ExperienceOrb`, and `ItemEntity`.

```java
var spawned = Entity.builder(EntityType.ZOMBIE)
        .noGravity(true)
        .initialize((process, entity) -> entity.set(DataComponents.CUSTOM_NAME, Component.text(process.brandName())))
        .spawn(instance, new Pos(0, 40, 0));
```

## Events

| Upstream / existing use | This fork |
| --- | --- |
| `GlobalEventHandler` | `process.eventHandler()`. |
| `EventDispatcher.call(event)` / `callCancellable(...)` | Call `process.eventHandler()`; the static dispatcher is removed. |
| `EventNode.all("name")` | Still ownerless at construction. Attach with `process.eventHandler().addChild(node)`. |
| Contextual node conditions | `EventNode.contextual(name, filter, (process, event, handler) -> ...)`. |
| `addListener(Type.class, event -> ...)` | Still supported; `(process, event) -> ...` receives dispatch context. Builder handlers, filters, and expiration predicates also have contextual overloads. |
| `node.call(event)` / listener handles | Implicit dispatch requires a bound owner. For standalone dispatch, use `node.call(process, event)` / `handle.call(process, event)`. |
| Custom `EventListener.run(event)` | Implement `run(process, event)` instead. Built-in expiration counts are per registration; custom registration state can use `newRegistration()`. |
| Custom filters and bindings | `EventFilter.getHandler(process, event)`, `EventFilter.fromContextual(...)`, and contextual `EventBinding.consumer(...)`. Existing ordinary filter factories remain. |

Event ownership is validated during dispatch. Each node has at most one parent; detach before attaching elsewhere. `EventNode.process()` can be null for standalone nodes, which require explicit dispatch context. Native Image ownership metadata is still supported.

## Commands

Command and argument definitions remain reusable across processes. The executing manager supplies `CommandContext` before parsing, including nested/grouped/mapped arguments.

| API / behavior | Change |
| --- | --- |
| Executors | Existing `(sender, context)` shape; use `context.process()` and `.commandManager()`. |
| Manual contexts | `new CommandContext(manager, input[, purpose])`. `fork()` copies argument maps; `copy(...)` rejects another manager. |
| Conditions | `(sender, context)` replaces `(sender, nullableInput)`. Use `context.getInput()` and `.purpose()`. |
| Visibility and suggestions | Purpose is `PARSING`, `EXECUTION`, `SUGGESTION`, or `DECLARATION`; declaration input is empty. Only send execution feedback for the appropriate purpose. |
| Argument error callbacks | `(sender, context, exception)`. Applicable conditions must allow the callback; it takes precedence over the default executor for invalid arguments. |
| Unknown-command callbacks | `(sender, context)` replaces `(sender, commandString)`. |
| `SimpleCommand` | `process(sender, context, command, args)` and `hasAccess(sender, context)`. |
| Custom arguments | Override `parse(sender, context, input)` when context is needed. Stateless `parse(sender, input)` remains usable. Default-value functions can accept `(sender, context)`. |
| Low-level parser | Supply the manager to `CommandParser.parse(manager, sender, graph, input)`. |
| Entity selectors | `new EntityFinder(process)`; parsed finders deliberately retain their process and query its live state. They are not portable definitions. |

## Scheduling

| Operation | Current behavior |
| --- | --- |
| Schedule work | `process.schedulerManager().buildTask(...).delay(...).repeat(...).schedule()`; normal task APIs remain. |
| Independent scheduler | `Scheduler.newScheduler(process)` or `process.schedulerManager().createScheduler()`. The caller drives its ticks. |
| Timer ownership | Child schedulers share one timer within their process. Closing A does not stop B's timers. |
| Close/cancel | Schedulers implement `AutoCloseable`; `isClosed()` reports closure. Pending work is cancelled, and new submissions/child schedulers are rejected after closure. |
| Object removal | Entity removal and instance unregistration close their associated schedulers. |
| In-flight callbacks | Cancellation/closure does not wait for or interrupt callbacks already admitted to execution; they cannot reschedule after closure. |
| Future schedules | `TaskSchedule.future(future)` resumes on success; failure/cancellation stops the task without modifying the source future. |
| Shutdown callbacks | Run in registration order after scheduler closure. A failing callback does not skip later callbacks. |

## Tags and serialization

Ordinary and contextual definitions both remain process-independent:

```java
static final Tag<Integer> SCORE = Tag.Integer("score");
static final ContextualTag<ItemStack> REWARD = Tag.ItemStack("reward");
```

| Operation | Current API / semantics |
| --- | --- |
| Ordinary tags | `Tag.Integer`, `Tag.String`, etc. keep `Tag<T>` and need no registries. Ordinary `TagSerializer<T>` remains. |
| Item/component tags | `Tag.ItemStack(...)` and `Tag.Component(...)` return `ContextualTag<T>`. Generic helpers must account for that distinct type. |
| Entity/player/inventory tags | `player.setTag(REWARD, item)` / `player.getTag(REWARD)` derive registries from the owner. |
| Standalone items and blocks | `item.withTag(REWARD, value, registries)` / `item.getTag(REWARD, registries)`; blocks have equivalent overloads. Items/blocks retain no process. |
| Builders and custom data | Contextual overloads on `ItemStack.Builder`, `CustomData`, and `TypedCustomData` accept registries. |
| Standalone handlers | `TagHandler.newHandler(registries)` or `TagHandler.fromCompound(nbt, registries)`. Unbound factories still support ordinary tags. |
| Bound accessors | `reader.withRegistries(registries)` / `writer.withRegistries(...)` / `handler.withRegistries(...)` share the underlying storage and retain the chosen registries. |
| Missing context | Contextual access without explicit or bound registries throws `IllegalStateException`; there is no singleton fallback. |
| Raw NBT | `REWARD.read(nbt, registries)` / `.write(builder, value, registries)`. `.asNbt()` exposes its serialized representation as an ordinary tag. |
| Composition | Lists, mappings, paths, defaults, structures, views, and updates preserve contextual conversion. Ordinary/contextual tags share one store. |
| Copies | Handler `copy()` / `readableCopy()` preserve the binding and copy the data. Rebinding chooses another registry context; it does not duplicate the underlying store. |
| Conversion/cache cost | Contextual writes encode immediately; reads decode each time. Only NBT is cached. Retain frequently used decoded values within their registry context and refresh after tag/registry changes. |
| Custom contextual serializers | `ContextualTagSerializer<T>.read(reader, registries)` / `.write(writer, value, registries)`; nested readers/writers are bound. `fromCompound(...)` accepts contextual conversion functions. |

The experimental reflective record factories explicitly select the return type:

| Factory | Accepted record fields |
| --- | --- |
| `Tag.Structure("key", MyRecord.class)` / `Tag.View(MyRecord.class)` | Ordinary fields only; rejects item/component fields even inside nested records. |
| `ContextualTag.Structure("key", MyRecord.class)` / `ContextualTag.View(MyRecord.class)` | Ordinary and contextual fields, including nested records; always returns `ContextualTag<T>`. |

Record reflection supports ordinary tag scalar types, NBT, and nested records; contextual factories additionally support `ItemStack` and `Component`. Recursive records and unsupported fields such as collections require a custom serializer. Reflection caches definitions/metadata, never registry-bound conversions.

| Conversion boundary | Explicit context |
| --- | --- |
| Item NBT | `item.toItemNBT(registries)` and `ItemStack.fromItemNBT(nbt, registries)`. |
| Item hashes | `ItemStack.Hash.of(item, registries)`. |
| Component NBT | `NbtComponentSerializer.nbt(registries)`. |
| Custom codecs | Use `RegistryTranscoder` with the consuming registries for registry-dependent NBT/JSON conversions. |

Default-process serializer overloads and their temporary factory deprecations are removed.

## Registries and predicates

Registry tags describe game-data membership; they are separate from custom `Tag<T>` storage.

| Area | Migration / semantics |
| --- | --- |
| Registry ownership | Dynamic registries and registry-tag membership belong to each process. Use `process.registries().blocks()` / `.material()` for contextual tag membership; immutable vanilla protocol definitions can remain shared. |
| Standalone conversions | `Registries.vanilla()` supplies registries without starting a server. Custom `Registries` implementations must also supply the static registries; `Registries.Delegating` can forward them. |
| Registration/freezing | Register entries before startup. `Registries.freeze(registries)` / `DynamicRegistry.freeze()` freeze entries; tags remain mutable. `isFrozen()` reports the effective state, subject to shared test/unsafe-operation settings. |
| Registry-tag definitions | `RegistryTag.reference(tagKey)` and `.direct(keys)` retain references/keys, not a registry. |
| Iterate/test membership | Replace iteration, `size()`, and `contains(key)` with `tag.resolve(registry)`, `.resolve(registry).size()`, and `.contains(registry, key)`. Missing references resolve empty. |
| Tag cache invalidation | Revisions track mutations automatically. `ConnectionManager.invalidateTags()` is removed. |
| Item/block/component predicates | Evaluation takes registries: `predicate.test(registries, value)`. Reusable definitions resolve against the consuming context. |
| Collection predicates | `test(collection, evaluator)` receives the element-predicate evaluator explicitly. |
| Tool rules | `tool.getSpeed(blockRegistry, block.registryKey())` / `.isCorrectForDrops(blockRegistry, block.registryKey())`. Overloads taking a `Block` are deprecated. |

## Adventure and audiences

| Upstream / operation | This fork |
| --- | --- |
| Static `Audiences.all()`, `.players()`, `.server()`, `.console()`, `.custom(...)`, `.single()`, `.iterable()` | Use `process.audiences()` with the same methods. Custom registrations belong to that process. |
| Grouping players | `PacketGroupingAudience.of(players)` can group players from multiple processes; manager operations use each player's owner. Entity sound emitters must match the recipients' owner. |
| Global translation settings | `process.translation().setTranslator(...)` / `.setDefaultLocale(...)`; `.translate(...)` and `.flattener()` provide explicit translation. |
| Global plain/legacy/ANSI serializers | Use the basic flattener. Bind `process.translation().flattener()` when process-specific translation is wanted. |
| `ClickEvent.callback(...)` | The global provider throws. Use `process.clickCallbackManager().createClickEvent(callback, options)`. |
| Adventure data-component values | Values remain ownerless. Decode with `MinestomDataComponentValue.from(key, value, registries)`; encode with `value.toGson(key, registries)` / `.toNbt(key, registries)`. |
| Hover serialization | Normal item-hover packet encoding supplies the buffer's registries. The global Adventure conversion provider rejects native conversions that need missing context. |

## Networking, ping, and LAN

| Operation | Migration / semantics |
| --- | --- |
| Normal sends | `player.sendPacket(...)` and `PacketSendingUtils.sendGroupedPacket(players, packet)` keep their signatures; recipient connections supply encoding context. |
| Broadcast | `PacketSendingUtils.broadcastPlayPacket(process, packet)`. |
| Connections/custom servers | `PlayerConnection`, `PlayerSocketConnection`, and socket `Server` constructors require a process. Connection ownership stays fixed. |
| Authentication utilities | `MojangCrypt` failures throw `IllegalStateException` instead of returning null and reporting through the default process. |
| Disconnect/shutdown | Pending login-plugin, cookie, known-pack, and resource-pack replies are cancelled. Reentrant callbacks preserve player teardown; closed processes reject late admission/configuration transitions. |
| Player removal | `ConnectionManager.removePlayer(connection)` returns the detached player, or null. |
| Encoding context | Low-level code uses `connection.packetContext()`: pool/registry ownership, protocol state, and negotiated compression. |
| Packet allocation | `PacketWriting.allocateTrimmedPacket(context, serverPacket)` replaces implicit-pool overloads. Explicit temporary-buffer overloads remain for custom/client packets. |
| Cached packets | `CachedPacket.packet/body/framed(context)` and `SendablePacket.extractServerPacket(context, packet)` replace state-only calls. Encoded caches cannot reuse another ownership/state/compression context. |
| Pre-encoded packets | `FramedPacket(context, packet, body)` / `BufferedPacket(context, buffer, index, length)` retain and validate encoding context. |
| Internal pooling/batching | `PacketVanilla.PACKET_POOL` and global viewable flushing are removed. The process owns `packetBuffers()` / `packetBatcher()`; `prepareViewablePacket` takes a process. These are internal APIs. |
| Status counts/samples | `Status.PlayerInfo.onlineCount(count)` / `.online(players, maxSamples)` replace global lookups. Connected status pings use their owner's players. |
| LAN | `process.lan().open([config])` / `.close()`; process shutdown closes advertisements. LAN ping events have a null connection. Response helpers take an explicit port. |
| Bound addresses | `process.server().socketAddress()` is the supplied bind address; `.getPort()` gives the actual bound port, including when binding port zero. |
| Tracking/metrics | `EntityTracker.newTracker(process)`; acquisition timing is on the owning `ThreadDispatcher` via `resetAcquiringTime()`. |
| Custom tick workers | `ThreadDispatcher.dispatcher(process, provider, threadCount)` supplies ownership and exception handling. `TickThread(name, exceptionHandler)` supports an explicit handler; ownerless constructors log failures locally. |

Reusable packet values still need semantically valid contents for their recipients: process-local entity IDs or already-resolved numeric registry IDs are not made portable by encoding context.
