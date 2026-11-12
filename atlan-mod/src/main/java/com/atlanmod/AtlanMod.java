package com.atlanmod;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.RotationAxis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class AtlanMod implements ModInitializer {
    public static final String MOD_ID = "atlan";

    /** Обе версии костюма (Mark 42 и Mark 85) — один тип сущности высотой 7 блоков. */
    public static final EntityType<AtlanEntity> ATLAN = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "atlan"),
            EntityType.Builder.create(AtlanEntity::new, SpawnGroup.MISC)
                    .dimensions(AtlanEntity.WIDTH, AtlanEntity.HEIGHT)
                    .passengerAttachments(4.0f * AtlanEntity.SCALE)   // высота кабины
                    .makeFireImmune()
                    .maxTrackingRange(16)
                    .trackingTickInterval(1)    // плавное движение в полёте
                    .build("atlan"));

    /** Mark 42 — дуговой реактор. */
    public static final Item ARC_REACTOR = Registry.register(
            Registries.ITEM, Identifier.of(MOD_ID, "arc_reactor"),
            new ArcReactorItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof(), AtlanEntity.VARIANT_MK42));

    /** Mark 85 — нано-реактор. */
    public static final Item NANO_REACTOR = Registry.register(
            Registries.ITEM, Identifier.of(MOD_ID, "nano_reactor"),
            new ArcReactorItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof(), AtlanEntity.VARIANT_MK85));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(ATLAN, AtlanEntity.createAtlanAttributes());

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(ARC_REACTOR);
            entries.add(NANO_REACTOR);
        });

        // Пилот внутри костюма не получает урон от удушья, когда костюм пролетает сквозь блоки
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity.getVehicle() instanceof AtlanEntity && source.isOf(DamageTypes.IN_WALL)));

        PayloadTypeRegistry.playC2S().register(AtlanActionPayload.ID, AtlanActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AtlanHudPayload.ID, AtlanHudPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AtlanActionPayload.ID, (payload, context) -> {
            var player = context.player();
            if (player.getVehicle() instanceof AtlanEntity atlan
                    && atlan.getControllingPassenger() == player) {
                atlan.performAction(player, payload.action());
            }
        });
    }

    // ======================================================================
    // Остальные классы мода — вложенные (всё в одном файле)
    // ======================================================================

    public record AtlanActionPayload(int action) implements CustomPayload {
        public static final int PUNCH = 0;     // ЛКМ: кулак или удар мечом, если меч обнажён
        public static final int SLAM = 1;
        public static final int SWORD_TOGGLE = 2;
        public static final int FLIGHT_TOGGLE = 3;
        public static final int REPULSOR = 4;
        public static final int UNIBEAM = 5;
        public static final int EJECT = 6;
        public static final int SHIELD = 7;    // Mk85/Mk50: нано-щит
        public static final int HAMMER = 8;    // Mk85/Mk50: нано-молот
        public static final int VOLLEY = 9;    // Mk85/Mk50: залп из крыльев
        public static final int MASK = 10;     // Fisk: маска (забрало) вкл/выкл
        public static final int HOVER = 11;    // Fisk: режим парения
        public static final int BOOST_ON = 12; // Fisk: ускорение (спринт) в полёте
        public static final int BOOST_OFF = 13;
        public static final int ROLL_LEFT = 14;  // Fisk: бочка влево (двойное A в полёте)
        public static final int ROLL_RIGHT = 15; // Fisk: бочка вправо (двойное D в полёте)

        public static final CustomPayload.Id<AtlanActionPayload> ID =
                new CustomPayload.Id<>(Identifier.of(AtlanMod.MOD_ID, "action"));

        public static final PacketCodec<RegistryByteBuf, AtlanActionPayload> CODEC =
                PacketCodecs.VAR_INT.xmap(AtlanActionPayload::new, AtlanActionPayload::action).cast();

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Оставшиеся тики кулдаунов способностей — сервер → пилот, для HUD. */
    public record AtlanHudPayload(int slam, int repulsor, int beam, int sword, int hammer, int volley) implements CustomPayload {
        public static final CustomPayload.Id<AtlanHudPayload> ID =
                new CustomPayload.Id<>(Identifier.of(AtlanMod.MOD_ID, "hud"));

        public static final PacketCodec<RegistryByteBuf, AtlanHudPayload> CODEC = PacketCodec.of(
                (v, buf) -> {
                    buf.writeVarInt(v.slam());
                    buf.writeVarInt(v.repulsor());
                    buf.writeVarInt(v.beam());
                    buf.writeVarInt(v.sword());
                    buf.writeVarInt(v.hammer());
                    buf.writeVarInt(v.volley());
                },
                buf -> new AtlanHudPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Пульт вызова Атлана. variant 0 — дуговой реактор (Mark 42), variant 1 — нано-реактор (Mark 85).
     * ПКМ: вызвать костюм. Shift+ПКМ: вкл/выкл автономный режим.
     */
    public static class ArcReactorItem extends Item {
        private final int variant;

        public ArcReactorItem(Settings settings, int variant) {
            super(settings);
            this.variant = variant;
        }

        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient && user instanceof ServerPlayerEntity player) {
                if (player.isSneaking()) {
                    AtlanSummoner.toggleAutonomous(player);
                } else {
                    AtlanSummoner.summon(player, variant);
                }
                player.getItemCooldownManager().set(this, 60);
            }
            return TypedActionResult.success(stack);
        }

        @Override
        public boolean hasGlint(ItemStack stack) {
            return true;
        }
    }

    /** Вызов костюма: Mark 42 прилетает по частям, Mark 85 собирается из наночастиц мгновенно. */
    public static final class AtlanSummoner {
        private AtlanSummoner() {}

        public static AtlanEntity findOwned(ServerPlayerEntity player) {
            MinecraftServer server = player.getServer();
            if (server == null) return null;
            for (ServerWorld w : server.getWorlds()) {
                List<? extends AtlanEntity> list = w.getEntitiesByType(
                        TypeFilter.instanceOf(AtlanEntity.class),
                        a -> player.getUuid().equals(a.getOwnerUuid()) && a.isAlive());
                if (!list.isEmpty()) return list.get(0);
            }
            return null;
        }

        public static void summon(ServerPlayerEntity player, int variant) {
            if (player.getVehicle() instanceof AtlanEntity inside) {
                if (variant == AtlanEntity.VARIANT_MK85 && inside.isNano()) {
                    inside.nanoDisassemble(player);   // повторное нажатие нано-реактора — нанит-превращение обратно
                } else {
                    player.sendMessage(Text.translatable("message.atlan.already_inside"), true);
                }
                return;
            }
            ServerWorld world = (ServerWorld) player.getWorld();
            AtlanEntity atlan = findOwned(player);
            // у игрока один костюм: другое измерение или другая версия — собираем заново
            if (atlan != null && (atlan.getWorld() != world || atlan.getVariant() != variant)) {
                atlan.discard();
                atlan = null;
            }
            if (atlan == null) {
                atlan = AtlanMod.ATLAN.create(world);
                if (atlan == null) return;
                atlan.setVariant(variant);
                if (variant == AtlanEntity.VARIANT_MK85) {
                    atlan.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
                } else {
                    double ang = world.getRandom().nextDouble() * Math.PI * 2.0;
                    double y = Math.min(player.getY() + 30, world.getTopY() - 8);
                    atlan.refreshPositionAndAngles(
                            player.getX() + Math.cos(ang) * 48, y, player.getZ() + Math.sin(ang) * 48, 0, 0);
                }
                world.spawnEntity(atlan);
            }
            atlan.beginSummon(player);
            player.sendMessage(Text.translatable(variant == AtlanEntity.VARIANT_MK85
                    ? "message.atlan.nano_assembling" : "message.atlan.incoming"), true);
        }

        public static void toggleAutonomous(ServerPlayerEntity player) {
            AtlanEntity atlan = findOwned(player);
            if (atlan == null) return;
            boolean now = !atlan.isAutonomous();
            atlan.setAutonomous(now);
            player.sendMessage(Text.translatable(now ? "message.atlan.autonomous_on" : "message.atlan.autonomous_off"), true);
        }
    }

    /**
     * Атлан — гигантский костюм-мех высотой 7 блоков. Две версии:
     *  0 — Mark 42: детали прилетают и собираются вокруг владельца, юнибим, меч.
     *  1 — Mark 85: нанотех. Мгновенная сборка из наночастиц + способности Mark 50:
     *      нано-щит, нано-молот, залп из крыльев, нано-клинок, усиленный репульсор/гаунтлет-луч.
     * Общее: кулак, удар по земле, прыжок, полёт СКВОЗЬ БЛОКИ, репульсоры, автономный режим.
     */
    public static class AtlanEntity extends PathAwareEntity {
        private static final TrackedData<Integer> SLAM_TICKS = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Integer> SCATTER = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Boolean> FLYING = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        private static final TrackedData<Boolean> SWORD_DRAWN = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        private static final TrackedData<Integer> SWORD_LASH = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Integer> BEAM = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Integer> VARIANT = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Boolean> SHIELD = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        private static final TrackedData<Integer> HAMMER = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Integer> FLASH = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Boolean> HOVER = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        private static final TrackedData<Boolean> MASK = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        private static final TrackedData<Integer> ROLL = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        private static final TrackedData<Integer> ROLL_DIR = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);
        public static final int ROLL_DURATION = 12;
        private static final TrackedData<Integer> VOLLEY = DataTracker.registerData(AtlanEntity.class, TrackedDataHandlerRegistry.INTEGER);

        public static final int VARIANT_MK42 = 0;
        public static final int VARIANT_MK85 = 1;

        /** Рост костюма в блоках и множитель относительно базовой модели (6 блоков). */
        public static final float HEIGHT = 7.0f;
        public static final float WIDTH = 3.5f;
        public static final float SCALE = HEIGHT / 6.0f;
        private static final double S = SCALE;

        // --- анимации ---
        public static final int SLAM_DURATION = 20;
        public static final int SLAM_HIT_TICK = 8;
        public static final int HAMMER_DURATION = 20;
        public static final int HAMMER_HIT_TICK = 8;
        public static final int LASH_DURATION = 16;
        public static final int BEAM_CHARGE = 15;
        public static final int BEAM_TOTAL = 45;
        public static final int SCATTER_MAX = 20;

        // --- пластинчатый меч ---
        public static final float SWORD_BASE = 3.0f;
        public static final float SWORD_EXT = 14.0f;
        private static final float SWORD_DAMAGE = 28.0f;
        public static final int SWORD_COOLDOWN = 18;

        // --- баланс ---
        private static final float PUNCH_DAMAGE = 30.0f;
        private static final float SLAM_MAX_DAMAGE = 30.0f;
        private static final double SLAM_RADIUS = 7.0;
        private static final float REPULSOR_DAMAGE = 14.0f;
        private static final float REPULSOR_DAMAGE_NANO = 18.0f;
        private static final float BEAM_DAMAGE = 5.0f;
        private static final float BEAM_DAMAGE_NANO = 7.0f;
        private static final float HAMMER_MAX_DAMAGE = 45.0f;
        private static final double HAMMER_RADIUS = 5.5;
        private static final float VOLLEY_DAMAGE = 10.0f;
        private static final float SHIELD_DAMAGE_FACTOR = 0.25f;
        private static final double FLIGHT_SPEED = 1.1;
        private static final int PUNCH_COOLDOWN = 14;
        public static final int SLAM_COOLDOWN = 60;
        private static final int LEAP_COOLDOWN = 40;
        public static final int REPULSOR_COOLDOWN = 8;
        public static final int BEAM_COOLDOWN = 160;
        public static final int HAMMER_COOLDOWN = 80;
        public static final int VOLLEY_COOLDOWN = 100;

        private int punchCooldown, slamCooldown, leapCooldown, swordCooldown, repulsorCooldown,
                beamCooldown, autoFireCooldown, hammerCooldown, volleyCooldown;
        private int volleyLeft;
        private boolean rightHandNext = true;
        private boolean boosting;
        private int beamTotal = BEAM_TOTAL;
        private boolean airborne;
        private double airStartY;
        private final Set<Integer> swordHits = new HashSet<>();

        @Nullable
        private UUID ownerUuid;
        private boolean autonomous = true;
        private int summonPhase;   // 0 — нет, 1 — летит деталями (Mk42), 2 — собирается вокруг владельца
        private int summonTicks;

        public AtlanEntity(EntityType<? extends AtlanEntity> type, World world) {
            super(type, world);
            this.experiencePoints = 0;
            this.setPersistent();
        }

        public static DefaultAttributeContainer.Builder createAtlanAttributes() {
            return MobEntity.createMobAttributes()
                    .add(EntityAttributes.GENERIC_MAX_HEALTH, 450.0)
                    .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.17)
                    .add(EntityAttributes.GENERIC_ARMOR, 20.0)
                    .add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, 10.0)
                    .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
                    .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.75)
                    .add(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 32.0)
                    .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 25.0);
        }

        @Override
        protected void initDataTracker(DataTracker.Builder builder) {
            super.initDataTracker(builder);
            builder.add(SLAM_TICKS, 0);
            builder.add(SCATTER, 0);
            builder.add(FLYING, false);
            builder.add(SWORD_DRAWN, false);
            builder.add(SWORD_LASH, 0);
            builder.add(BEAM, 0);
            builder.add(VARIANT, VARIANT_MK42);
            builder.add(SHIELD, false);
            builder.add(HAMMER, 0);
            builder.add(VOLLEY, 0);
            builder.add(FLASH, 0);
            builder.add(HOVER, false);
            builder.add(MASK, true);
            builder.add(ROLL, 0);
            builder.add(ROLL_DIR, 1);
        }

        // ------------------------------------------------------------ getters (клиент/модель)

        public int getSlamTicks() { return this.dataTracker.get(SLAM_TICKS); }
        public int getScatter() { return this.dataTracker.get(SCATTER); }
        public boolean isFlying() { return this.dataTracker.get(FLYING); }
        public boolean isSwordDrawn() { return this.dataTracker.get(SWORD_DRAWN); }
        public int getSwordLash() { return this.dataTracker.get(SWORD_LASH); }
        public int getBeam() { return this.dataTracker.get(BEAM); }
        public int getVariant() { return this.dataTracker.get(VARIANT); }
        public void setVariant(int v) { this.dataTracker.set(VARIANT, v); }
        public boolean isNano() { return getVariant() == VARIANT_MK85; }
        public boolean isShielded() { return this.dataTracker.get(SHIELD); }
        public int getHammer() { return this.dataTracker.get(HAMMER); }
        public int getVolley() { return this.dataTracker.get(VOLLEY); }
        /** Вспышка свечения при выстреле (6..0), для рендера. */
        public int getFlash() { return this.dataTracker.get(FLASH); }
        public boolean isHover() { return this.dataTracker.get(HOVER); }
        public boolean isMaskOn() { return this.dataTracker.get(MASK); }
        public int getRoll() { return this.dataTracker.get(ROLL); }
        public int getRollDir() { return this.dataTracker.get(ROLL_DIR); }

        public static float swordExtension(float elapsed) {
            if (elapsed <= 0) return 0f;
            if (elapsed < 5f) return elapsed / 5f;
            if (elapsed < 8f) return 1f;
            if (elapsed < LASH_DURATION) return 1f - (elapsed - 8f) / (LASH_DURATION - 8f);
            return 0f;
        }

        public float getSwordExtension(float partial) {
            int lash = getSwordLash();
            return lash > 0 ? swordExtension(LASH_DURATION - lash + partial) : 0f;
        }

        @Nullable public UUID getOwnerUuid() { return ownerUuid; }
        public boolean isAutonomous() { return autonomous; }
        public void setAutonomous(boolean value) { this.autonomous = value; }

        @Nullable
        private ServerPlayerEntity getOwnerPlayer() {
            if (ownerUuid == null || !(this.getWorld() instanceof ServerWorld sw)) return null;
            return sw.getServer().getPlayerManager().getPlayer(ownerUuid);
        }

        // ------------------------------------------------------------ NBT

        @Override
        public void writeCustomDataToNbt(NbtCompound nbt) {
            super.writeCustomDataToNbt(nbt);
            if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
            nbt.putBoolean("Autonomous", autonomous);
            nbt.putBoolean("SwordDrawn", isSwordDrawn());
            nbt.putInt("Variant", getVariant());
        }

        @Override
        public void readCustomDataFromNbt(NbtCompound nbt) {
            super.readCustomDataFromNbt(nbt);
            if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
            if (nbt.contains("Autonomous")) autonomous = nbt.getBoolean("Autonomous");
            this.dataTracker.set(SWORD_DRAWN, nbt.getBoolean("SwordDrawn"));
            this.dataTracker.set(VARIANT, nbt.getInt("Variant"));
        }

        @Override
        public boolean canImmediatelyDespawn(double distanceSquared) {
            return false;
        }

        // ------------------------------------------------------------ щит (Mark 50)

        @Override
        public boolean damage(DamageSource source, float amount) {
            if (isShielded() && !source.isOf(DamageTypes.OUT_OF_WORLD)) {
                amount *= SHIELD_DAMAGE_FACTOR;
                if (this.getWorld() instanceof ServerWorld w) {
                    w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 3.5 * S, getZ(), 8, 1.0, 1.5, 1.0, 0.2);
                }
            }
            return super.damage(source, amount);
        }

        // ------------------------------------------------------------ посадка / управление

        @Override
        protected ActionResult interactMob(PlayerEntity player, Hand hand) {
            boolean allowed = ownerUuid == null || ownerUuid.equals(player.getUuid());
            if (allowed && summonPhase == 0 && !this.hasPassengers() && !player.shouldCancelInteraction()) {
                if (!this.getWorld().isClient) {
                    player.startRiding(this);
                }
                return ActionResult.success(this.getWorld().isClient);
            }
            return super.interactMob(player, hand);
        }

        @Override
        protected boolean canAddPassenger(Entity passenger) {
            return this.getPassengerList().isEmpty() && passenger instanceof PlayerEntity;
        }

        @Nullable
        @Override
        public LivingEntity getControllingPassenger() {
            return this.getFirstPassenger() instanceof PlayerEntity player ? player : null;
        }

        /**
         * В полёте движением управляет СЕРВЕР: клиентские пакеты движения транспорта сервер откатывает,
         * если костюм залетел в блоки. Серверное управление позволяет пролетать сквозь блоки без "резинки".
         */
        @Override
        public boolean isLogicalSideForUpdatingMovement() {
            if (isFlying() && this.getControllingPassenger() instanceof PlayerEntity) {
                return !this.getWorld().isClient;
            }
            return super.isLogicalSideForUpdatingMovement();
        }

        @Override
        public void travel(Vec3d movementInput) {
            if (this.isAlive() && this.getControllingPassenger() instanceof PlayerEntity rider) {
                float yaw = rider.getYaw();
                this.setYaw(yaw);
                this.prevYaw = yaw;
                this.setPitch(rider.getPitch());
                this.setRotation(this.getYaw(), this.getPitch());
                this.bodyYaw = yaw;
                this.headYaw = yaw;

                if (this.isFlying()) {
                    flyTravel(rider);
                } else {
                    float sideways = rider.sidewaysSpeed * 0.3f;
                    float forward = rider.forwardSpeed;
                    if (forward <= 0.0f) forward *= 0.5f;
                    if (isShielded()) { forward *= 0.7f; sideways *= 0.7f; }
                    this.setMovementSpeed((float) this.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED));
                    super.travel(new Vec3d(sideways, movementInput.y, forward));
                }
            } else {
                super.travel(movementInput);
            }
        }

        private void flyTravel(PlayerEntity rider) {
            Vec3d look = Vec3d.fromPolar(rider.getPitch(), rider.getYaw());
            if (isHover()) look = new Vec3d(look.x, look.y * 0.25, look.z);   // парение: почти без набора высоты
            double r = Math.toRadians(rider.getYaw());
            Vec3d left = new Vec3d(Math.cos(r), 0, Math.sin(r));
            double speed = FLIGHT_SPEED * (boosting ? 1.8 : 1.0) * (isHover() ? 0.55 : 1.0);
            Vec3d wish = look.multiply(rider.forwardSpeed).add(left.multiply(rider.sidewaysSpeed)).multiply(speed);
            this.setVelocity(this.getVelocity().multiply(0.85).add(wish.multiply(0.15)));
            this.move(MovementType.SELF, this.getVelocity());   // noClip: сквозь блоки
            this.fallDistance = 0;
        }

        /** Костюм внутри блоков (или рядом с застреванием)? */
        private boolean isStuck() {
            return !this.getWorld().isSpaceEmpty(this, this.getBoundingBox().contract(0.1));
        }

        // ------------------------------------------------------------ tick

        @Override
        public void tick() {
            // в полёте и при сборке костюм проходит сквозь блоки
            if (!this.getWorld().isClient) {
                this.noClip = this.isFlying() || this.summonPhase != 0;
            }
            super.tick();
            if (punchCooldown > 0) punchCooldown--;
            if (slamCooldown > 0) slamCooldown--;
            if (leapCooldown > 0) leapCooldown--;
            if (swordCooldown > 0) swordCooldown--;
            if (repulsorCooldown > 0) repulsorCooldown--;
            if (beamCooldown > 0) beamCooldown--;
            if (autoFireCooldown > 0) autoFireCooldown--;
            if (hammerCooldown > 0) hammerCooldown--;
            if (volleyCooldown > 0) volleyCooldown--;

            if (this.getWorld().isClient) return;

            int flash = getFlash();
            if (flash > 0) this.dataTracker.set(FLASH, flash - 1);
            if (this.age % 2 == 0 && this.getControllingPassenger() instanceof ServerPlayerEntity pilot) {
                ServerPlayNetworking.send(pilot, new AtlanHudPayload(
                        slamCooldown, repulsorCooldown, beamCooldown, swordCooldown, hammerCooldown, volleyCooldown));
            }

            tickSummon();
            if (summonPhase != 0) return;
            tickRoll();
            tickBuffs();
            tickLanding();

            // застряли в блоках — автоматически включаем полёт сквозь блоки, чтобы выбраться
            if (!isFlying() && this.age % 10 == 0 && isStuck()) {
                this.dataTracker.set(FLYING, true);
                this.setNoGravity(true);
            }

            tickSlam();
            tickHammer();
            tickSword();
            tickBeam();
            tickVolley();
            tickAutonomous();
        }

        private void tickSlam() {
            int slam = getSlamTicks();
            if (slam > 0) {
                slam--;
                this.dataTracker.set(SLAM_TICKS, slam);
                if (slam == SLAM_HIT_TICK) doSlamImpact();
            }
        }

        // ------------------------------------------------------------ вызов

        public void beginSummon(ServerPlayerEntity owner) {
            this.ownerUuid = owner.getUuid();
            this.summonTicks = 0;
            this.removeAllPassengers();
            this.setInvulnerable(true);
            this.setNoGravity(true);
            this.dataTracker.set(FLYING, false);
            this.dataTracker.set(BEAM, 0);
            this.dataTracker.set(SWORD_LASH, 0);
            this.dataTracker.set(SHIELD, false);
            if (isNano()) {
                // Mark 85: наночастицы собирают костюм мгновенно вокруг владельца
                this.summonPhase = 2;
                this.dataTracker.set(SCATTER, SCATTER_MAX);
                this.setVelocity(Vec3d.ZERO);
                this.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), 0);
            } else {
                this.summonPhase = 1;
            }
        }

        private void endSummon() {
            this.summonPhase = 0;
            this.setInvulnerable(false);
            this.dataTracker.set(SCATTER, 0);
            this.setNoGravity(false);
            this.dataTracker.set(FLYING, false);
            if (isStuck()) {                       // собрались внутри блоков — остаёмся в режиме полёта сквозь блоки
                this.dataTracker.set(FLYING, true);
                this.setNoGravity(true);
            }
        }

        private void tickSummon() {
            if (summonPhase == 0) return;
            ServerPlayerEntity owner = getOwnerPlayer();
            if (owner == null || !owner.isAlive() || owner.getWorld() != this.getWorld()) {
                endSummon();
                return;
            }
            ServerWorld w = (ServerWorld) this.getWorld();
            summonTicks++;

            if (summonPhase == 1) {
                this.dataTracker.set(SCATTER, Math.min(SCATTER_MAX, getScatter() + 2));
                Vec3d d = owner.getPos().add(0, 1, 0).subtract(this.getPos());
                double dist = d.length();
                if (dist < 4.0 || summonTicks > 600) {
                    summonPhase = 2;
                    this.setVelocity(Vec3d.ZERO);
                    this.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), 0);
                    w.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT,
                            SoundCategory.HOSTILE, 3.0f, 0.8f);
                } else {
                    Vec3d v = d.multiply(1.0 / dist).multiply(Math.min(1.8, 0.5 + dist * 0.04));
                    this.setVelocity(v);
                    faceTowards(owner.getPos());
                    w.spawnParticles(ParticleTypes.FLAME, getX(), getY() + 3, getZ(), 6, 1.0, 1.5, 1.0, 0.02);
                    w.spawnParticles(ParticleTypes.SMOKE, getX(), getY() + 3, getZ(), 4, 1.0, 1.5, 1.0, 0.01);
                }
            } else {
                this.dataTracker.set(SCATTER, Math.max(0, getScatter() - 2));
                this.setVelocity(Vec3d.ZERO);
                this.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), 0);
                if (isNano()) {
                    w.spawnParticles(ParticleTypes.END_ROD, getX(), getY() + 3.5 * S, getZ(), 14, 1.6, 2.4, 1.6, 0.05);
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 3.5 * S, getZ(), 6, 1.2, 2.0, 1.2, 0.1);
                if (getScatter() == 0) {
                    endSummon();
                    owner.stopRiding();
                    owner.startRiding(this, true);
                    w.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 3.0f, 0.6f);
                    w.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 3.0f, 1.0f);
                    w.spawnParticles(ParticleTypes.EXPLOSION, getX(), getY() + 2, getZ(), 2, 1.0, 1.0, 1.0, 0.0);
                }
            }
        }

        // ------------------------------------------------------------ действия пилота

        public void performAction(PlayerEntity rider, int action) {
            if (summonPhase != 0) return;
            switch (action) {
                case AtlanActionPayload.PUNCH -> {
                    if (isSwordDrawn()) startLash(); else punch(rider);
                }
                case AtlanActionPayload.SLAM -> startSlam();
                case AtlanActionPayload.SWORD_TOGGLE -> toggleSword();
                case AtlanActionPayload.FLIGHT_TOGGLE -> toggleFlight(rider);
                case AtlanActionPayload.REPULSOR -> {
                    if (repulsorCooldown == 0) {
                        repulsorCooldown = REPULSOR_COOLDOWN;
                        fireRepulsor(rider, aimDir(rider), null);
                    }
                }
                case AtlanActionPayload.UNIBEAM -> startBeam();
                case AtlanActionPayload.EJECT -> eject(rider);
                case AtlanActionPayload.SHIELD, AtlanActionPayload.HAMMER, AtlanActionPayload.VOLLEY -> {
                    if (!isNano()) {
                        if (rider instanceof ServerPlayerEntity sp) sp.sendMessage(Text.translatable("message.atlan.nano_only"), true);
                    } else if (action == AtlanActionPayload.SHIELD) toggleShield();
                    else if (action == AtlanActionPayload.HAMMER) startHammer();
                    else startVolley();
                }
                case AtlanActionPayload.MASK -> toggleMask();
                case AtlanActionPayload.HOVER -> toggleHover();
                case AtlanActionPayload.BOOST_ON -> boosting = true;
                case AtlanActionPayload.BOOST_OFF -> boosting = false;
                case AtlanActionPayload.ROLL_LEFT -> startRoll(1);
                case AtlanActionPayload.ROLL_RIGHT -> startRoll(-1);
                default -> { }
            }
        }

        private void eject(PlayerEntity rider) {
            rider.stopRiding();
            this.autonomous = true;
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE,
                    SoundCategory.HOSTILE, 2.0f, 1.0f);
        }

        private void toggleFlight(PlayerEntity rider) {
            boolean now = !isFlying();
            if (!now && isStuck()) {   // нельзя приземлиться внутри блоков
                if (rider instanceof ServerPlayerEntity sp) sp.sendMessage(Text.translatable("message.atlan.cannot_land"), true);
                return;
            }
            this.dataTracker.set(FLYING, now);
            if (!now) this.dataTracker.set(HOVER, false);
            this.setNoGravity(now);
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT,
                    SoundCategory.HOSTILE, 2.0f, now ? 1.4f : 0.7f);
        }

        private void toggleSword() {
            if (getSwordLash() > 0) return;
            boolean now = !isSwordDrawn();
            this.dataTracker.set(SWORD_DRAWN, now);
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_TRIDENT_RETURN,
                    SoundCategory.HOSTILE, 2.0f, now ? 0.8f : 0.6f);
        }

        private void toggleShield() {
            boolean now = !isShielded();
            this.dataTracker.set(SHIELD, now);
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT,
                    SoundCategory.HOSTILE, 2.0f, now ? 1.8f : 0.9f);
        }

        // ------------------------------------------------------------ способности как в Fiskheroes

        private void toggleMask() {
            boolean now = !isMaskOn();
            this.dataTracker.set(MASK, now);
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_TRIDENT_RETURN,
                    SoundCategory.HOSTILE, 2.0f, now ? 1.4f : 1.1f);
        }

        /** Парение: полёт с точным управлением (медленнее, без набора высоты от взгляда). */
        private void toggleHover() {
            boolean now = !isHover();
            this.dataTracker.set(HOVER, now);
            if (now && !isFlying()) {
                this.dataTracker.set(FLYING, true);
                this.setNoGravity(true);
            }
        }

        /** Бочка в полёте: рывок вбок + анимация вращения. dir: +1 влево, -1 вправо. */
        private void startRoll(int dir) {
            if (!isFlying() || getRoll() > 0) return;
            this.dataTracker.set(ROLL, ROLL_DURATION);
            this.dataTracker.set(ROLL_DIR, dir);
            this.setVelocity(this.getVelocity().add(rightVec().multiply(-dir * 1.1)));
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                    SoundCategory.HOSTILE, 2.0f, 0.5f);
        }

        private void tickRoll() {
            int r = getRoll();
            if (r > 0) this.dataTracker.set(ROLL, r - 1);
        }

        /** Пока пилот внутри: сопротивление урону, огнестойкость, дыхание под водой, регенерация. */
        private void tickBuffs() {
            PlayerEntity rider = riderOrNull();
            if (rider == null) {
                boosting = false;
                return;
            }
            if (this.age % 10 == 0) {
                rider.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 3, true, false, false));
                rider.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 40, 0, true, false, false));
            }
            rider.setAir(rider.getMaxAir());
            if (this.age % 20 == 0) {
                this.heal(isNano() ? 2.0f : 1.0f);
                if (this.age % 40 == 0) rider.heal(isNano() ? 1.0f : 0.5f);
            }
        }

        /** Суперменская посадка: падение с высоты не наносит урона, а при приземлении — ударная волна. */
        private void tickLanding() {
            if (isFlying()) {
                airborne = false;
                return;
            }
            if (!this.isOnGround()) {
                if (!airborne) {
                    airborne = true;
                    airStartY = getY();
                } else if (getY() > airStartY) {
                    airStartY = getY();
                }
            } else if (airborne) {
                airborne = false;
                double drop = airStartY - getY();
                if (drop > 5.0 && this.getWorld() instanceof ServerWorld w) {
                    float dmg = (float) Math.min(30.0, 6.0 + drop);
                    areaImpact(w, Vec3d.ZERO, 5.0, 6.0f, dmg, 0.9, false);
                }
            }
        }

        /** Нанит-превращение Mark 85 «вкл/выкл»: наночастицы собираются в костюм (вызов) или разбираются. */
        public void nanoDisassemble(ServerPlayerEntity owner) {
            if (this.getWorld() instanceof ServerWorld w) {
                w.spawnParticles(ParticleTypes.END_ROD, getX(), getY() + 3.5 * S, getZ(), 60, 1.8, 3.0, 1.8, 0.1);
                w.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE,
                        SoundCategory.HOSTILE, 3.0f, 1.2f);
            }
            owner.stopRiding();
            this.discard();
        }

        // ------------------------------------------------------------ вспомогательное

        private Vec3d forward() { return Vec3d.fromPolar(0.0f, this.getYaw()); }

        private Vec3d rightVec() {
            double r = Math.toRadians(this.getYaw());
            return new Vec3d(-Math.cos(r), 0, -Math.sin(r));
        }

        private Vec3d chestPos() { return this.getPos().add(0, 4.1 * S, 0); }

        private Vec3d handPos(boolean right, Vec3d dir) {
            return chestPos().add(rightVec().multiply((right ? 1.9 : -1.9) * S)).add(dir.multiply(2.0 * S));
        }

        @Nullable
        private PlayerEntity riderOrNull() {
            return this.getControllingPassenger() instanceof PlayerEntity p ? p : null;
        }

        private Vec3d aimDir(@Nullable PlayerEntity rider) {
            return rider != null ? rider.getRotationVec(1.0f) : Vec3d.fromPolar(this.getPitch(), this.getYaw());
        }

        private void faceTowards(Vec3d p) {
            double dx = p.x - getX();
            double dz = p.z - getZ();
            float yaw = (float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0f;
            this.setYaw(yaw);
            this.bodyYaw = yaw;
            this.headYaw = yaw;
        }

        private DamageSource sourceFor(@Nullable PlayerEntity rider) {
            return rider != null ? this.getDamageSources().playerAttack(rider) : this.getDamageSources().mobAttack(this);
        }

        private boolean isValidTarget(Entity e) {
            return e instanceof LivingEntity
                    && !(e instanceof AtlanEntity)
                    && !this.hasPassenger(e)
                    && !(ownerUuid != null && ownerUuid.equals(e.getUuid()));
        }

        private Vec3d clipBlocks(ServerWorld w, Vec3d from, Vec3d to) {
            BlockHitResult hit = w.raycast(new RaycastContext(from, to,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
            return hit.getType() == HitResult.Type.MISS ? to : hit.getPos();
        }

        private List<LivingEntity> entitiesAlong(ServerWorld w, Vec3d a, Vec3d b, double radius) {
            List<LivingEntity> out = new ArrayList<>();
            Vec3d d = b.subtract(a);
            double len = d.length();
            if (len < 1.0E-4) return out;
            Vec3d n = d.multiply(1.0 / len);
            for (double s = 0; s <= len + 0.5; s += 1.0) {
                Vec3d p = a.add(n.multiply(Math.min(s, len)));
                for (Entity e : w.getOtherEntities(this, new Box(p, p).expand(radius), this::isValidTarget)) {
                    if (!out.contains(e)) out.add((LivingEntity) e);
                }
            }
            return out;
        }

        // ------------------------------------------------------------ кулак

        private void punch(PlayerEntity rider) {
            if (punchCooldown > 0 || getSlamTicks() > 0 || getHammer() > 0 || !(this.getWorld() instanceof ServerWorld world)) return;
            punchCooldown = PUNCH_COOLDOWN;
            this.swingHand(Hand.MAIN_HAND);

            Vec3d fwd = forward();
            Vec3d center = this.getPos().add(fwd.multiply(3.3 * S)).add(0, 3.0 * S, 0);
            Box box = new Box(center, center).expand(2.6 * S);
            for (Entity e : world.getOtherEntities(this, box, this::isValidTarget)) {
                LivingEntity target = (LivingEntity) e;
                target.damage(sourceFor(rider), PUNCH_DAMAGE);
                target.takeKnockback(1.8, -fwd.x, -fwd.z);
                target.setVelocity(target.getVelocity().add(0, 0.4, 0));
                target.velocityModified = true;
            }
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK,
                    SoundCategory.HOSTILE, 2.0f, 0.5f);
        }

        // ------------------------------------------------------------ удар по земле

        private void startSlam() {
            if (slamCooldown > 0 || getSlamTicks() > 0 || getHammer() > 0) return;
            slamCooldown = SLAM_COOLDOWN;
            this.dataTracker.set(SLAM_TICKS, SLAM_DURATION);
        }

        private void doSlamImpact() {
            if (!(this.getWorld() instanceof ServerWorld world)) return;
            areaImpact(world, forward().multiply(3.5 * S), SLAM_RADIUS, 8.0f, SLAM_MAX_DAMAGE, 1.2, false);
        }

        /** Общий удар по площади (удар по земле и нано-молот). */
        private void areaImpact(ServerWorld world, Vec3d offset, double radius, float minDmg, float maxDmg,
                                double pushStrength, boolean lightning) {
            PlayerEntity rider = riderOrNull();
            Vec3d center = this.getPos().add(offset);
            Box area = new Box(center, center).expand(radius, 3.0, radius);

            for (Entity e : world.getOtherEntities(this, area, this::isValidTarget)) {
                double dist = e.getPos().distanceTo(center);
                if (dist > radius) continue;
                double scale = 1.0 - dist / radius;
                LivingEntity target = (LivingEntity) e;
                target.damage(sourceFor(rider), (float) (minDmg + (maxDmg - minDmg) * scale));
                Vec3d away = e.getPos().subtract(center).multiply(1, 0, 1);
                if (away.lengthSquared() < 1.0E-4) away = new Vec3d(0, 0, 1);
                away = away.normalize();
                target.setVelocity(target.getVelocity().add(away.x * pushStrength * scale,
                        0.6 + 0.5 * scale, away.z * pushStrength * scale));
                target.velocityModified = true;
                if (lightning) target.setOnFireFor(2);
            }
            world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 3.0f, lightning ? 0.3f : 0.5f);
            for (int ring = 1; ring <= (int) radius; ring++) {
                int points = ring * 6;
                for (int i = 0; i < points; i++) {
                    double a = (Math.PI * 2 * i) / points;
                    world.spawnParticles(lightning ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.POOF,
                            center.x + Math.cos(a) * ring, center.y + 0.2, center.z + Math.sin(a) * ring, 1, 0.1, 0.1, 0.1, 0.05);
                }
            }
            world.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.5, center.z, 3, 0.8, 0.2, 0.8, 0.0);
        }

        // ------------------------------------------------------------ нано-молот (Mark 50)

        private void startHammer() {
            if (hammerCooldown > 0 || getHammer() > 0 || getSlamTicks() > 0) return;
            hammerCooldown = HAMMER_COOLDOWN;
            this.dataTracker.set(HAMMER, HAMMER_DURATION);
        }

        private void tickHammer() {
            int h = getHammer();
            if (h <= 0) return;
            h--;
            this.dataTracker.set(HAMMER, h);
            if (h == HAMMER_HIT_TICK && this.getWorld() instanceof ServerWorld w) {
                areaImpact(w, forward().multiply(4.2 * S), HAMMER_RADIUS, 20.0f, HAMMER_MAX_DAMAGE, 1.6, true);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, getX() + forward().x * 4.2 * S, getY() + 1.0,
                        getZ() + forward().z * 4.2 * S, 60, 2.0, 1.5, 2.0, 0.4);
            }
        }

        // ------------------------------------------------------------ залп из крыльев (Mark 50)

        private void startVolley() {
            if (volleyCooldown > 0 || volleyLeft > 0) return;
            volleyCooldown = VOLLEY_COOLDOWN;
            volleyLeft = 6;
            this.dataTracker.set(VOLLEY, volleyLeft);
        }

        private void tickVolley() {
            if (volleyLeft <= 0) return;
            if (this.age % 3 != 0) return;
            if (!(this.getWorld() instanceof ServerWorld w)) return;
            PlayerEntity rider = riderOrNull();

            List<HostileEntity> targets = new ArrayList<>(w.getEntitiesByClass(HostileEntity.class,
                    this.getBoundingBox().expand(32), LivingEntity::isAlive));
            targets.sort(Comparator.comparingDouble(h -> h.squaredDistanceTo(this)));

            Vec3d tip = chestPos().add(rightVec().multiply((volleyLeft % 2 == 0 ? 3.4 : -3.4) * S)).add(0, 0.8, 0);
            if (!targets.isEmpty()) {
                LivingEntity t = targets.get((6 - volleyLeft) % targets.size());
                shootBolt(rider, tip, Vec3d.ZERO, t, VOLLEY_DAMAGE);
            } else {
                Vec3d dir = aimDir(rider).add(
                        (w.getRandom().nextDouble() - 0.5) * 0.2,
                        (w.getRandom().nextDouble() - 0.5) * 0.2,
                        (w.getRandom().nextDouble() - 0.5) * 0.2).normalize();
                shootBolt(rider, tip, dir, null, VOLLEY_DAMAGE);
            }
            volleyLeft--;
            this.dataTracker.set(VOLLEY, volleyLeft);
        }

        // ------------------------------------------------------------ прыжок (клиент пилота)

        public void tryLeap() {
            if (leapCooldown > 0 || isFlying() || !this.isOnGround()) return;
            leapCooldown = LEAP_COOLDOWN;
            Vec3d fwd = forward();
            this.setVelocity(fwd.x * 1.4, 1.0, fwd.z * 1.4);
            this.getWorld().playSound(getX(), getY(), getZ(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK,
                    SoundCategory.HOSTILE, 2.0f, 0.4f, false);
        }

        // ------------------------------------------------------------ пластинчатый меч

        private void startLash() {
            if (!isSwordDrawn() || getSwordLash() > 0 || swordCooldown > 0 || getHammer() > 0) return;
            swordCooldown = SWORD_COOLDOWN;
            swordHits.clear();
            this.dataTracker.set(SWORD_LASH, LASH_DURATION);
            this.getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                    SoundCategory.HOSTILE, 2.0f, 0.6f);
        }

        private void tickSword() {
            int lash = getSwordLash();
            if (lash <= 0) return;
            lash--;
            this.dataTracker.set(SWORD_LASH, lash);
            float f = swordExtension(LASH_DURATION - lash);
            if (f > 0 && this.getWorld() instanceof ServerWorld w) sweepSword(w, f);
            if (lash == 0) swordHits.clear();
        }

        /** Клинок вытягивается: дальность удара = текущая длина растяжения. */
        private void sweepSword(ServerWorld w, float f) {
            PlayerEntity rider = riderOrNull();
            Vec3d dir = aimDir(rider);
            Vec3d start = handPos(true, dir).add(dir.multiply(0.5));
            double length = SWORD_BASE + f * SWORD_EXT;
            Vec3d end = clipBlocks(w, start, start.add(dir.multiply(length)));

            for (LivingEntity t : entitiesAlong(w, start, end, 1.3)) {
                if (!swordHits.add(t.getId())) continue;
                t.damage(sourceFor(rider), SWORD_DAMAGE);
                t.takeKnockback(1.2, -dir.x, -dir.z);
                t.setVelocity(t.getVelocity().add(0, 0.3, 0));
                t.velocityModified = true;
                w.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getBodyY(0.5), t.getZ(), 12, 0.3, 0.4, 0.3, 0.3);
            }
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, end.x, end.y, end.z, 4, 0.2, 0.2, 0.2, 0.1);
            if (f < 1.0f) w.spawnParticles(ParticleTypes.SWEEP_ATTACK, end.x, end.y, end.z, 1, 0, 0, 0, 0);
        }

        // ------------------------------------------------------------ репульсоры

        private void fireRepulsor(@Nullable PlayerEntity rider, Vec3d dir, @Nullable LivingEntity forced) {
            Vec3d origin = handPos(rightHandNext, dir);
            rightHandNext = !rightHandNext;
            shootBolt(rider, origin, dir, forced, isNano() ? REPULSOR_DAMAGE_NANO : REPULSOR_DAMAGE);
        }

        /** Мгновенный энергетический выстрел. forced != null — точно в цель, иначе вдоль dir. */
        private void shootBolt(@Nullable PlayerEntity rider, Vec3d origin, Vec3d dir, @Nullable LivingEntity forced, float damage) {
            if (!(this.getWorld() instanceof ServerWorld w)) return;
            Vec3d end;
            if (forced != null) {
                end = forced.getPos().add(0, forced.getHeight() * 0.5, 0);
                dir = end.subtract(origin).normalize();
                forced.damage(sourceFor(rider), damage);
            } else {
                Vec3d far = clipBlocks(w, origin, origin.add(dir.multiply(40)));
                List<LivingEntity> hits = entitiesAlong(w, origin, far, 0.8);
                end = far;
                if (!hits.isEmpty()) {
                    LivingEntity t = hits.get(0);
                    end = t.getPos().add(0, t.getHeight() * 0.5, 0);
                    t.damage(sourceFor(rider), damage);
                    t.takeKnockback(0.6, -dir.x, -dir.z);
                }
            }
            this.dataTracker.set(FLASH, 6);
            Vec3d line = end.subtract(origin);
            double len = line.length();
            if (len > 1.0E-4) {
                for (double s = 0; s < len; s += 0.75) {
                    Vec3d p = origin.add(line.multiply(s / len));
                    w.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
            w.spawnParticles(ParticleTypes.EXPLOSION, end.x, end.y, end.z, 1, 0, 0, 0, 0);
            w.playSound(null, origin.x, origin.y, origin.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                    SoundCategory.HOSTILE, 1.5f, 1.4f);
        }

        // ------------------------------------------------------------ юнибим / гаунтлет-луч

        private void startBeam() {
            if (beamCooldown > 0 || getBeam() > 0) return;
            beamCooldown = BEAM_COOLDOWN;
            beamTotal = isNano() ? 60 : BEAM_TOTAL;
            this.dataTracker.set(BEAM, beamTotal);
        }

        private void tickBeam() {
            int b = getBeam();
            if (b <= 0) return;
            PlayerEntity rider = riderOrNull();
            if (rider == null || !(this.getWorld() instanceof ServerWorld w)) {
                this.dataTracker.set(BEAM, 0);
                return;
            }
            b--;
            this.dataTracker.set(BEAM, b);
            int elapsed = beamTotal - b;
            Vec3d dir = aimDir(rider);
            Vec3d origin = chestPos().add(forward().multiply(1.3 * S));

            if (elapsed < (isNano() ? 20 : BEAM_CHARGE)) {
                w.spawnParticles(ParticleTypes.END_ROD, origin.x + dir.x, origin.y + dir.y, origin.z + dir.z,
                        3, 0.6, 0.6, 0.6, 0.02);
                if (elapsed == 1) {
                    w.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BLOCK_BEACON_POWER_SELECT,
                            SoundCategory.HOSTILE, 3.0f, 0.6f);
                }
                return;
            }

            Vec3d end = clipBlocks(w, origin, origin.add(dir.multiply(48)));
            Vec3d line = end.subtract(origin);
            double len = line.length();
            for (double s = 0; s < len; s += 1.0) {
                Vec3d p = origin.add(line.multiply(s / len));
                w.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
            }
            float dmg = isNano() ? BEAM_DAMAGE_NANO : BEAM_DAMAGE;
            for (LivingEntity t : entitiesAlong(w, origin, end, 1.2)) {
                t.timeUntilRegen = 0;
                t.damage(sourceFor(rider), dmg);
                t.setOnFireFor(3);
            }
            if (elapsed % 5 == 0) {
                w.spawnParticles(ParticleTypes.EXPLOSION, end.x, end.y, end.z, 1, 0.3, 0.3, 0.3, 0);
                w.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BLOCK_BEACON_AMBIENT,
                        SoundCategory.HOSTILE, 3.0f, 1.6f);
            }
        }

        // ------------------------------------------------------------ автономный режим

        private void tickAutonomous() {
            if (this.hasPassengers()) return;
            if (ownerUuid == null) {
                if (isFlying() && !isStuck()) {
                    this.dataTracker.set(FLYING, false);
                    this.setNoGravity(false);
                }
                return;
            }
            if (!autonomous) return;
            ServerPlayerEntity owner = getOwnerPlayer();
            if (owner == null || owner.getWorld() != this.getWorld()) return;

            double dist = this.distanceTo(owner);
            if (dist > 100) {
                this.refreshPositionAndAngles(owner.getX() + 3, owner.getY(), owner.getZ() + 3, getYaw(), 0);
                return;
            }
            if (dist > 12) {
                if (!isFlying()) {
                    this.dataTracker.set(FLYING, true);
                    this.setNoGravity(true);
                }
                Vec3d d = owner.getPos().add(0, 2, 0).subtract(this.getPos());
                this.setVelocity(d.normalize().multiply(Math.min(0.9, 0.2 + dist * 0.03)));
                faceTowards(owner.getPos());
            } else if (isFlying() && dist < 7 && !isStuck()) {
                this.dataTracker.set(FLYING, false);
                this.setNoGravity(false);
            } else if (isFlying()) {
                this.setVelocity(this.getVelocity().multiply(0.8));
            }

            if (autoFireCooldown == 0) {
                HostileEntity target = null;
                double best = Double.MAX_VALUE;
                for (HostileEntity h : this.getWorld().getEntitiesByClass(HostileEntity.class,
                        this.getBoundingBox().expand(24), LivingEntity::isAlive)) {
                    double dd = h.squaredDistanceTo(this);
                    if (dd < best) { best = dd; target = h; }
                }
                if (target != null) {
                    faceTowards(target.getPos());
                    autoFireCooldown = 15;
                    fireRepulsor(null, Vec3d.ZERO, target);
                }
            }
        }

        // ------------------------------------------------------------ звуки

        @Override
        protected void playStepSound(BlockPos pos, BlockState state) {
            this.playSound(SoundEvents.ENTITY_IRON_GOLEM_STEP, 1.5f, 0.5f);
        }

        @Override
        protected SoundEvent getHurtSound(DamageSource source) {
            return SoundEvents.ENTITY_IRON_GOLEM_HURT;
        }

        @Override
        protected SoundEvent getDeathSound() {
            return SoundEvents.ENTITY_IRON_GOLEM_DEATH;
        }
    }

    /** Модель Атлана. 16 px = 1 блок, высота ~5.6 блока. Текстура 256x256. */
    public static class AtlanModel extends EntityModel<AtlanEntity> {
        public static final EntityModelLayer LAYER =
                new EntityModelLayer(Identifier.of(AtlanMod.MOD_ID, "atlan"), "main");

        private static final int PLATES = 17;   // пластин в клинке (макс. длина = SWORD_BASE + SWORD_EXT)

        private final ModelPart root;
        private final ModelPart head, body, rightArm, leftArm, rightLeg, leftLeg, hilt;
        private final ModelPart wingR, wingL, shield, hammerHandle, hammerHead, faceplate;
        private final ModelPart[] plates = new ModelPart[PLATES];

        public AtlanModel(ModelPart root) {
            this.root = root;
            this.head = root.getChild("head");
            this.body = root.getChild("body");
            this.rightArm = root.getChild("right_arm");
            this.leftArm = root.getChild("left_arm");
            this.rightLeg = root.getChild("right_leg");
            this.leftLeg = root.getChild("left_leg");
            this.hilt = this.rightArm.getChild("hilt");
            this.wingR = this.body.getChild("wing_r");
            this.wingL = this.body.getChild("wing_l");
            this.shield = this.leftArm.getChild("shield");
            this.hammerHandle = this.rightArm.getChild("hammer_handle");
            this.hammerHead = this.rightArm.getChild("hammer_head");
            this.faceplate = this.head.getChild("faceplate");
            for (int i = 0; i < PLATES; i++) {
                this.plates[i] = this.rightArm.getChild("plate" + i);
            }
        }

        public static TexturedModelData getTexturedModelData() {
            ModelData data = new ModelData();
            ModelPartData r = data.getRoot();

            ModelPartData head = r.addChild("head",
                    ModelPartBuilder.create().uv(0, 0).cuboid(-9, -18, -9, 18, 18, 18),
                    ModelTransform.pivot(0, -48, 0));
            // рога шлема Атлана
            head.addChild("horn_r",
                    ModelPartBuilder.create().uv(210, 100).cuboid(-2, -12, -2, 4, 12, 4),
                    ModelTransform.of(-8, -16, -1, -0.15f, 0, 0.55f));
            head.addChild("horn_l",
                    ModelPartBuilder.create().uv(210, 100).mirrored().cuboid(-2, -12, -2, 4, 12, 4),
                    ModelTransform.of(8, -16, -1, -0.15f, 0, -0.55f));
            // откидная маска (забрало)
            head.addChild("faceplate",
                    ModelPartBuilder.create().uv(210, 120).cuboid(-8, -16, -11, 16, 14, 2),
                    ModelTransform.NONE);

            ModelPartData body = r.addChild("body",
                    ModelPartBuilder.create().uv(0, 40).cuboid(-22, -36, -12, 44, 36, 24)
                            .uv(100, 100).cuboid(-14, -34, 12, 28, 26, 10),
                    ModelTransform.pivot(0, -12, 0));
            // нано-крылья (Mark 50)
            body.addChild("wing_r",
                    ModelPartBuilder.create().uv(0, 200).cuboid(-40, -1.5f, -6, 40, 3, 12),
                    ModelTransform.pivot(-8, -30, 18));
            body.addChild("wing_l",
                    ModelPartBuilder.create().uv(0, 200).mirrored().cuboid(0, -1.5f, -6, 40, 3, 12),
                    ModelTransform.pivot(8, -30, 18));

            ModelPartData rightArm = r.addChild("right_arm",
                    ModelPartBuilder.create().uv(0, 100).cuboid(-9, 0, -9, 18, 40, 18),
                    ModelTransform.pivot(-31, -44, 0));
            rightArm.addChild("pad",
                    ModelPartBuilder.create().uv(0, 160).cuboid(-11, -4, -11, 22, 10, 22),
                    ModelTransform.NONE);
            // рукоять и пластины клинка торчат из кулака вдоль руки
            rightArm.addChild("hilt",
                    ModelPartBuilder.create().uv(200, 30).cuboid(-4, 37, -4, 8, 4, 8),
                    ModelTransform.NONE);
            rightArm.addChild("hammer_handle",
                    ModelPartBuilder.create().uv(190, 216).cuboid(-2, 40, -2, 4, 30, 4),
                    ModelTransform.NONE);
            rightArm.addChild("hammer_head",
                    ModelPartBuilder.create().uv(100, 216).cuboid(-10, 66, -10, 20, 16, 20),
                    ModelTransform.NONE);
            for (int i = 0; i < PLATES; i++) {
                rightArm.addChild("plate" + i,
                        ModelPartBuilder.create().uv(200, 0).cuboid(-1, 40 + 16 * i, -3, 2, 15, 6),
                        ModelTransform.NONE);
            }

            ModelPartData leftArm = r.addChild("left_arm",
                    ModelPartBuilder.create().uv(0, 100).mirrored().cuboid(-9, 0, -9, 18, 40, 18),
                    ModelTransform.pivot(31, -44, 0));
            leftArm.addChild("pad",
                    ModelPartBuilder.create().uv(0, 160).mirrored().cuboid(-11, -4, -11, 22, 10, 22),
                    ModelTransform.NONE);
            leftArm.addChild("shield",
                    ModelPartBuilder.create().uv(0, 220).cuboid(-14, 6, -17, 28, 30, 3),
                    ModelTransform.NONE);

            ModelPartData rightLeg = r.addChild("right_leg",
                    ModelPartBuilder.create().uv(140, 0).cuboid(-7, 0, -7, 14, 36, 14),
                    ModelTransform.pivot(-12, -12, 0));
            rightLeg.addChild("foot",
                    ModelPartBuilder.create().uv(100, 140).cuboid(-9, 30, -12, 18, 6, 24),
                    ModelTransform.NONE);

            ModelPartData leftLeg = r.addChild("left_leg",
                    ModelPartBuilder.create().uv(140, 0).mirrored().cuboid(-7, 0, -7, 14, 36, 14),
                    ModelTransform.pivot(12, -12, 0));
            leftLeg.addChild("foot",
                    ModelPartBuilder.create().uv(100, 140).mirrored().cuboid(-9, 30, -12, 18, 6, 24),
                    ModelTransform.NONE);

            return TexturedModelData.of(data, 256, 256);
        }

        @Override
        public void setAngles(AtlanEntity entity, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
            this.root.traverse().forEach(ModelPart::resetTransform);
            final float deg = (float) Math.PI / 180f;
            float partial = ageInTicks - entity.age;

            this.head.yaw = netHeadYaw * deg;
            this.head.pitch = headPitch * deg * 0.5f;

            // ходьба
            float swing = MathHelper.cos(limbSwing * 0.5f) * limbSwingAmount;
            this.rightLeg.pitch = swing;
            this.leftLeg.pitch = -swing;
            this.rightArm.pitch = -swing * 0.6f;
            this.leftArm.pitch = swing * 0.6f;
            this.body.roll = MathHelper.cos(limbSwing * 0.5f) * 0.04f * limbSwingAmount;

            // полёт: наклон корпуса, ноги сзади
            if (entity.isFlying()) {
                this.body.pitch = 0.3f;
                this.rightLeg.pitch = 0.5f + MathHelper.sin(ageInTicks * 0.3f) * 0.05f;
                this.leftLeg.pitch = 0.5f - MathHelper.sin(ageInTicks * 0.3f) * 0.05f;
                this.rightArm.pitch = 0.3f;
                this.leftArm.pitch = 0.3f;
            }

            // удар кулаком
            float punch = entity.getHandSwingProgress(partial);
            if (punch > 0.0f) {
                float s = MathHelper.sin(punch * (float) Math.PI);
                this.rightArm.pitch = -2.0f * s;
                this.body.yaw = -0.35f * s;
                this.head.yaw += 0.35f * s;
            }

            // удар обеими руками по земле
            int slam = entity.getSlamTicks();
            if (slam > 0) {
                float t = MathHelper.clamp((AtlanEntity.SLAM_DURATION - slam + partial) / AtlanEntity.SLAM_DURATION, 0f, 1f);
                float armPitch = t < 0.6f
                        ? MathHelper.lerp(t / 0.6f, 0.0f, -3.0f)
                        : MathHelper.lerp((t - 0.6f) / 0.4f, -3.0f, -0.2f);
                this.rightArm.pitch = armPitch;
                this.leftArm.pitch = armPitch;
                this.body.pitch = t < 0.6f
                        ? MathHelper.lerp(t / 0.6f, 0f, -0.15f)
                        : MathHelper.lerp((t - 0.6f) / 0.4f, -0.15f, 0.25f);
            }

            // юнибим: руки в стороны, корпус выпрямлен
            if (entity.getBeam() > 0) {
                this.rightArm.roll = 0.5f;
                this.leftArm.roll = -0.5f;
                this.body.pitch = 0f;
            }

            // пластинчатый меч
            boolean drawn = entity.isSwordDrawn();
            float ext = entity.getSwordExtension(partial);
            this.hilt.visible = drawn;
            int visiblePlates = (int) (AtlanEntity.SWORD_BASE + ext * AtlanEntity.SWORD_EXT);
            for (int i = 0; i < PLATES; i++) {
                this.plates[i].visible = drawn && entity.getHammer() == 0 && i < visiblePlates;
            }
            if (drawn) {
                float aim = -1.5707964f + headPitch * deg;   // рука целится туда же, куда смотрит пилот
                this.rightArm.pitch = MathHelper.lerp(ext, -0.9f, aim);
                if (ext > 0f) {
                    this.body.yaw = -0.3f * ext;
                }
            }

            // нано-молот (Mark 50): замах правой рукой и удар
            int hammer = entity.getHammer();
            boolean hammerOut = hammer > 0;
            this.hammerHandle.visible = hammerOut;
            this.hammerHead.visible = hammerOut;
            if (hammerOut) {
                float t = MathHelper.clamp((AtlanEntity.HAMMER_DURATION - hammer + partial) / AtlanEntity.HAMMER_DURATION, 0f, 1f);
                this.rightArm.pitch = t < 0.6f
                        ? MathHelper.lerp(t / 0.6f, 0.0f, -3.0f)
                        : MathHelper.lerp((t - 0.6f) / 0.4f, -3.0f, -0.2f);
                this.body.pitch = t < 0.6f
                        ? MathHelper.lerp(t / 0.6f, 0f, -0.1f)
                        : MathHelper.lerp((t - 0.6f) / 0.4f, -0.1f, 0.3f);
            }

            // нано-щит (Mark 50) на левой руке
            boolean shielded = entity.isShielded();
            this.shield.visible = shielded;
            if (shielded) {
                this.leftArm.pitch = -0.6f;
                this.leftArm.roll = -0.15f;
            }

            // нано-крылья: видны в полёте и во время залпа
            boolean wings = entity.isNano() && (entity.isFlying() || entity.getVolley() > 0);
            this.wingR.visible = wings;
            this.wingL.visible = wings;
            if (wings) {
                float flap = MathHelper.sin(ageInTicks * 0.3f) * 0.08f;
                float spread = entity.getVolley() > 0 ? 0.15f : 0.45f;
                this.wingR.roll = spread + flap;
                this.wingL.roll = -spread - flap;
            }

            this.faceplate.visible = entity.isMaskOn();

            // сборка/разлёт деталей (Mark 42 прилетает по частям)
            float sc = entity.getScatter() / (float) AtlanEntity.SCATTER_MAX;
            if (sc > 0f) {
                float j = MathHelper.sin(ageInTicks * 0.5f);
                this.head.pivotY -= 30f * sc;
                this.head.yaw = ageInTicks * 0.25f * sc;
                this.rightArm.pivotX -= 46f * sc;
                this.rightArm.pivotY -= 8f * sc;
                this.rightArm.roll = -0.5f * sc + j * 0.1f * sc;
                this.leftArm.pivotX += 46f * sc;
                this.leftArm.pivotY -= 8f * sc;
                this.leftArm.roll = 0.5f * sc - j * 0.1f * sc;
                this.rightLeg.pivotX -= 28f * sc;
                this.rightLeg.pivotY += 20f * sc;
                this.rightLeg.pitch += j * 0.2f * sc;
                this.leftLeg.pivotX += 28f * sc;
                this.leftLeg.pivotY += 20f * sc;
                this.leftLeg.pitch -= j * 0.2f * sc;
                this.body.yaw += j * 0.1f * sc;
            }
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
            this.root.render(matrices, vertices, light, overlay, color);
        }
    }

    public static class AtlanRenderer extends MobEntityRenderer<AtlanEntity, AtlanModel> {
        private static final Identifier TEX_MK42 = Identifier.of(AtlanMod.MOD_ID, "textures/entity/atlan.png");
        private static final Identifier TEX_MK85 = Identifier.of(AtlanMod.MOD_ID, "textures/entity/atlan_mk85.png");

        public AtlanRenderer(EntityRendererFactory.Context ctx) {
            super(ctx, new AtlanModel(ctx.getPart(AtlanModel.LAYER)), 2.3f);
            this.addFeature(new AtlanGlowFeature(this));
        }

        @Override
        public Identifier getTexture(AtlanEntity entity) {
            return entity.isNano() ? TEX_MK85 : TEX_MK42;
        }

        /** Наклон корпуса в полёте и бочка (как в Fiskheroes). */
        @Override
        protected void setupTransforms(AtlanEntity entity, MatrixStack matrices, float animationProgress,
                                       float bodyYaw, float tickDelta, float scale) {
            super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
            float lean = 0f;
            float roll = 0f;
            if (entity.isFlying()) {
                double dx = entity.getX() - entity.prevX;
                double dz = entity.getZ() - entity.prevZ;
                float speed = (float) Math.sqrt(dx * dx + dz * dz);
                lean = MathHelper.clamp(speed / 0.9f, 0f, 1f) * (entity.isHover() ? 15f : 65f);
            }
            int rt = entity.getRoll();
            if (rt > 0) {
                float frac = (AtlanEntity.ROLL_DURATION - rt + tickDelta) / AtlanEntity.ROLL_DURATION;
                roll = entity.getRollDir() * 360f * MathHelper.clamp(frac, 0f, 1f);
            }
            if (lean != 0f || roll != 0f) {
                matrices.translate(0, AtlanEntity.HEIGHT * 0.5, 0);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-lean));
                matrices.translate(0, -AtlanEntity.HEIGHT * 0.5, 0);
            }
        }

        /** Базовая модель 6 блоков → костюм высотой 7 блоков. */
        @Override
        protected void scale(AtlanEntity entity, MatrixStack matrices, float amount) {
            matrices.scale(AtlanEntity.SCALE, AtlanEntity.SCALE, AtlanEntity.SCALE);
        }

        /** Слой с отсечением задних граней — изнутри кабины не видно стенок головы. */
        @Override
        protected RenderLayer getRenderLayer(AtlanEntity entity, boolean showBody, boolean translucent, boolean showOutline) {
            return RenderLayer.getEntityCutout(getTexture(entity));
        }
    }

    /** Светящийся слой (глаза, ядро в груди, ладони, клинок, двигатель). Ярче при выстрелах и ударах. */
    public static class AtlanGlowFeature extends FeatureRenderer<AtlanEntity, AtlanModel> {
        private static final Identifier GLOW_MK42 = Identifier.of(AtlanMod.MOD_ID, "textures/entity/atlan_glow.png");
        private static final Identifier GLOW_MK85 = Identifier.of(AtlanMod.MOD_ID, "textures/entity/atlan_mk85_glow.png");

        public AtlanGlowFeature(FeatureRendererContext<AtlanEntity, AtlanModel> context) {
            super(context);
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, AtlanEntity entity,
                           float limbAngle, float limbDistance, float tickDelta, float animationProgress,
                           float headYaw, float headPitch) {
            if (entity.isInvisible()) return;

            float t = entity.age + tickDelta;
            float power = 0.55f + 0.08f * MathHelper.sin(t * 0.2f);          // спокойное дыхание
            power += 0.45f * (entity.getFlash() / 6.0f);                      // вспышка выстрела
            if (entity.getBeam() > 0) power += 0.5f;                          // зарядка/луч
            if (entity.getHammer() > 0 || entity.getSlamTicks() > 0) power += 0.3f;
            if (entity.getSwordLash() > 0 || entity.getVolley() > 0) power += 0.3f;
            if (entity.getScatter() > 0) power += 0.3f;                       // сборка
            power = MathHelper.clamp(power, 0.0f, 1.0f);

            int v = (int) (255 * power);
            int color = 0xFF000000 | (v << 16) | (v << 8) | v;

            Identifier tex = entity.isNano() ? GLOW_MK85 : GLOW_MK42;
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEyes(tex));
            this.getContextModel().render(matrices, vc, 0xF000F0, OverlayTexture.DEFAULT_UV, color);
        }
    }

    /** HUD пилота: здоровье костюма, статусы и кулдауны способностей. */
    public static final class AtlanHud {
        private AtlanHud() {}

        // клавиши выставляются из AtlanClient, чтобы HUD показывал актуальные привязки
        public static KeyBinding slamKey, repulsorKey, beamKey, swordKey, hammerKey, volleyKey, flightKey, shieldKey, hoverKey, maskKey;

        private static AtlanHudPayload data = new AtlanHudPayload(0, 0, 0, 0, 0, 0);

        public static void update(AtlanHudPayload payload) {
            data = payload;
        }

        private static String keyName(KeyBinding k) {
            return k == null ? "?" : k.getBoundKeyLocalizedText().getString();
        }

        public static void render(DrawContext ctx, RenderTickCounter tick) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            if (!(mc.player.getVehicle() instanceof AtlanEntity atlan)) return;

            TextRenderer tr = mc.textRenderer;
            int x = 10;
            int y = ctx.getScaledWindowHeight() / 2 - 70;

            // заголовок и здоровье костюма
            ctx.drawText(tr, Text.literal(atlan.isNano() ? "ATLAN Mk 85" : "ATLAN Mk 42"), x, y, 0xFFE8E8E8, true);
            float hp = Math.max(0f, atlan.getHealth() / atlan.getMaxHealth());
            bar(ctx, x, y + 12, 100, 5, hp, 0xFFCC3333);
            y += 24;

            // статусы
            y = status(ctx, tr, x, y, "hud.atlan.flight", keyName(flightKey), atlan.isFlying());
            y = status(ctx, tr, x, y, "hud.atlan.hover", keyName(hoverKey), atlan.isHover());
            y = status(ctx, tr, x, y, "hud.atlan.mask", keyName(maskKey), atlan.isMaskOn());
            y = status(ctx, tr, x, y, "hud.atlan.sword", keyName(swordKey), atlan.isSwordDrawn());
            if (atlan.isNano()) {
                y = status(ctx, tr, x, y, "hud.atlan.shield", keyName(shieldKey), atlan.isShielded());
            }
            y += 4;

            // кулдауны
            y = cooldown(ctx, tr, x, y, "hud.atlan.repulsor", keyName(repulsorKey), data.repulsor(), AtlanEntity.REPULSOR_COOLDOWN);
            y = cooldown(ctx, tr, x, y, "hud.atlan.slam", keyName(slamKey), data.slam(), AtlanEntity.SLAM_COOLDOWN);
            y = cooldown(ctx, tr, x, y, "hud.atlan.beam", keyName(beamKey), data.beam(), AtlanEntity.BEAM_COOLDOWN);
            y = cooldown(ctx, tr, x, y, "hud.atlan.blade", "LMB", data.sword(), AtlanEntity.SWORD_COOLDOWN);
            if (atlan.isNano()) {
                y = cooldown(ctx, tr, x, y, "hud.atlan.hammer", keyName(hammerKey), data.hammer(), AtlanEntity.HAMMER_COOLDOWN);
                cooldown(ctx, tr, x, y, "hud.atlan.volley", keyName(volleyKey), data.volley(), AtlanEntity.VOLLEY_COOLDOWN);
            }
        }

        private static int status(DrawContext ctx, TextRenderer tr, int x, int y, String key, String bind, boolean on) {
            int color = on ? 0xFF55FFFF : 0xFF777777;
            ctx.drawText(tr, Text.literal("[" + bind + "] ").append(Text.translatable(key))
                    .append(Text.literal(on ? "  ON" : "  OFF")), x, y, color, true);
            return y + 10;
        }

        private static int cooldown(DrawContext ctx, TextRenderer tr, int x, int y, String key, String bind, int remaining, int max) {
            boolean ready = remaining <= 0;
            ctx.drawText(tr, Text.literal("[" + bind + "] ").append(Text.translatable(key)),
                    x, y, ready ? 0xFFFFFFFF : 0xFFAAAAAA, true);
            float fill = ready ? 1f : 1f - Math.min(1f, remaining / (float) max);
            bar(ctx, x, y + 10, 100, 3, fill, ready ? 0xFF44DD66 : 0xFFDD9933);
            return y + 17;
        }

        private static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, int color) {
            ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xAA000000);
            ctx.fill(x, y, x + (int) (w * Math.min(1f, Math.max(0f, frac))), y + h, color);
        }
    }

    public static class AtlanClient implements ClientModInitializer {
        // двойные нажатия и ускорение (Fiskheroes): Space×2 — полёт, A/D×2 в полёте — бочка, Ctrl — ускорение
        private static int clientTick;
        private static boolean jumpWas, leftWas, rightWas, boostWas;
        private static int lastJump = -100, lastLeft = -100, lastRight = -100;

        private static KeyBinding key(String name, int code) {
            return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "category.atlan"));
        }

        private static void send(int action) {
            ClientPlayNetworking.send(new AtlanActionPayload(action));
        }

        @Override
        public void onInitializeClient() {
            EntityModelLayerRegistry.registerModelLayer(AtlanModel.LAYER, AtlanModel::getTexturedModelData);
            EntityRendererRegistry.register(AtlanMod.ATLAN, AtlanRenderer::new);

            KeyBinding slam = key("key.atlan.slam", InputUtil.GLFW_KEY_R);
            KeyBinding leap = key("key.atlan.leap", InputUtil.GLFW_KEY_G);
            KeyBinding sword = key("key.atlan.sword", InputUtil.GLFW_KEY_C);
            KeyBinding flight = key("key.atlan.flight", InputUtil.GLFW_KEY_V);
            KeyBinding repulsor = key("key.atlan.repulsor", InputUtil.GLFW_KEY_B);
            KeyBinding unibeam = key("key.atlan.unibeam", InputUtil.GLFW_KEY_N);
            KeyBinding eject = key("key.atlan.eject", InputUtil.GLFW_KEY_X);
            KeyBinding shield = key("key.atlan.shield", InputUtil.GLFW_KEY_Z);
            KeyBinding hammer = key("key.atlan.hammer", InputUtil.GLFW_KEY_H);
            KeyBinding volley = key("key.atlan.volley", InputUtil.GLFW_KEY_M);
            KeyBinding hover = key("key.atlan.hover", InputUtil.GLFW_KEY_J);
            KeyBinding mask = key("key.atlan.mask", InputUtil.GLFW_KEY_K);

            AtlanHud.slamKey = slam;
            AtlanHud.repulsorKey = repulsor;
            AtlanHud.beamKey = unibeam;
            AtlanHud.swordKey = sword;
            AtlanHud.flightKey = flight;
            AtlanHud.shieldKey = shield;
            AtlanHud.hammerKey = hammer;
            AtlanHud.volleyKey = volley;
            AtlanHud.hoverKey = hover;
            AtlanHud.maskKey = mask;

            ClientPlayNetworking.registerGlobalReceiver(AtlanHudPayload.ID, (payload, context) -> AtlanHud.update(payload));
            HudRenderCallback.EVENT.register(AtlanHud::render);

            // ЛКМ внутри Атлана: кулак или удар мечом (решает сервер), обычная атака отменяется
            ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
                if (player.getVehicle() instanceof AtlanEntity) {
                    send(AtlanActionPayload.PUNCH);
                    return true;
                }
                return false;
            });

            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player == null) return;
                Entity vehicle = client.player.getVehicle();
                boolean inside = vehicle instanceof AtlanEntity;
                clientTick++;
                if (inside) {
                    AtlanEntity suit = (AtlanEntity) vehicle;
                    boolean j = client.options.jumpKey.isPressed();
                    if (j && !jumpWas) {
                        if (clientTick - lastJump <= 7) { send(AtlanActionPayload.FLIGHT_TOGGLE); lastJump = -100; }
                        else lastJump = clientTick;
                    }
                    jumpWas = j;
                    boolean l = client.options.leftKey.isPressed();
                    if (l && !leftWas) {
                        if (clientTick - lastLeft <= 7 && suit.isFlying()) { send(AtlanActionPayload.ROLL_LEFT); lastLeft = -100; }
                        else lastLeft = clientTick;
                    }
                    leftWas = l;
                    boolean rr = client.options.rightKey.isPressed();
                    if (rr && !rightWas) {
                        if (clientTick - lastRight <= 7 && suit.isFlying()) { send(AtlanActionPayload.ROLL_RIGHT); lastRight = -100; }
                        else lastRight = clientTick;
                    }
                    rightWas = rr;
                    boolean b = client.options.sprintKey.isPressed();
                    if (b != boostWas) send(b ? AtlanActionPayload.BOOST_ON : AtlanActionPayload.BOOST_OFF);
                    boostWas = b;
                    while (hover.wasPressed()) send(AtlanActionPayload.HOVER);
                    while (mask.wasPressed()) send(AtlanActionPayload.MASK);
                }

                while (slam.wasPressed()) if (inside) send(AtlanActionPayload.SLAM);
                while (sword.wasPressed()) if (inside) send(AtlanActionPayload.SWORD_TOGGLE);
                while (flight.wasPressed()) if (inside) send(AtlanActionPayload.FLIGHT_TOGGLE);
                while (repulsor.wasPressed()) if (inside) send(AtlanActionPayload.REPULSOR);
                while (unibeam.wasPressed()) if (inside) send(AtlanActionPayload.UNIBEAM);
                while (eject.wasPressed()) if (inside) send(AtlanActionPayload.EJECT);
                while (shield.wasPressed()) if (inside) send(AtlanActionPayload.SHIELD);
                while (hammer.wasPressed()) if (inside) send(AtlanActionPayload.HAMMER);
                while (volley.wasPressed()) if (inside) send(AtlanActionPayload.VOLLEY);
                while (leap.wasPressed()) if (vehicle instanceof AtlanEntity atlan) atlan.tryLeap();
            });
        }
    }
}
