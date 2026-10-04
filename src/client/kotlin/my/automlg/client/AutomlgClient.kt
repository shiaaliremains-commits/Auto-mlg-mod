package my.automlg.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * Auto MLG (client only).
 * While falling, when the ground is close enough, it switches to a water bucket,
 * looks down, places the water, then picks it up again after landing and
 * restores your hotbar slot and camera angle.
 */
object AutomlgClient : ClientModInitializer {
    private const val MOD_ID = "automlg"

    /** place the water when the ground is at most this many blocks below your feet (reach limit is ~2.9) */
    private const val TRIGGER_DISTANCE = 2.8
    /** only act if the fall would hurt: fall distance so far + distance left must exceed this */
    private const val MIN_FALL = 3.5
    /** how many blocks below us we look for the ground */
    private const val MAX_SCAN = 8

    private val SAFE_BLOCKS = setOf(
        Blocks.SLIME_BLOCK, Blocks.HAY_BLOCK, Blocks.HONEY_BLOCK, Blocks.LADDER, Blocks.SCAFFOLDING
    )

    private enum class Phase { IDLE, PLACED }

    private lateinit var toggleKey: KeyMapping
    private var enabled = true
    private var phase = Phase.IDLE
    private var usedOffhand = false
    private var savedSlot = 0
    private var savedPitch = 0f
    private var ticksSincePlace = 0
    private var pickupTries = 0

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.automlg.toggle", InputConstants.KEY_G, category)
        )
        ClientTickEvents.END_CLIENT_TICK.register { client -> onTick(client) }
    }

    private fun onTick(client: Minecraft) {
        val player = client.player
        if (player == null) {
            phase = Phase.IDLE
            return
        }

        while (toggleKey.consumeClick()) {
            enabled = !enabled
            if (!enabled && phase == Phase.PLACED) restore(player)
            val msg = Component.literal("[Auto MLG] ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(
                    if (enabled) Component.literal("ON").withStyle(ChatFormatting.GREEN)
                    else Component.literal("OFF").withStyle(ChatFormatting.RED)
                )
            player.sendSystemMessage(msg)
        }

        if (!enabled) return
        val gm = client.gameMode ?: return

        when (phase) {
            Phase.IDLE -> tryPlace(client, player, gm)
            Phase.PLACED -> tryPickup(player, gm)
        }
    }

    private fun tryPlace(client: Minecraft, player: LocalPlayer, gm: MultiPlayerGameMode) {
        if (player.isCreative || player.isSpectator) return
        if (player.abilities.flying || player.isFallFlying) return
        if (player.onGround() || player.isInWater) return
        if (player.deltaMovement.y > -0.3) return

        val level = client.level ?: return
        if (level.dimension() == Level.NETHER) return // water evaporates there

        val dist = distanceToGround(level, player) ?: return
        if (dist > TRIGGER_DISTANCE) return
        if (player.fallDistance.toDouble() + dist <= MIN_FALL) return

        // find a water bucket: offhand first, then the hotbar
        val offhand = player.offhandItem.item == Items.WATER_BUCKET
        var slot = -1
        if (!offhand) {
            for (i in 0..8) {
                if (player.inventory.getItem(i).item == Items.WATER_BUCKET) {
                    slot = i
                    break
                }
            }
            if (slot < 0) return
        }

        usedOffhand = offhand
        savedSlot = player.inventory.selectedSlot
        savedPitch = player.xRot
        if (!offhand) player.inventory.selectedSlot = slot

        player.xRot = 90f
        gm.useItem(player, if (offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)

        phase = Phase.PLACED
        ticksSincePlace = 0
        pickupTries = 0
    }

    private fun tryPickup(player: LocalPlayer, gm: MultiPlayerGameMode) {
        ticksSincePlace++
        if (ticksSincePlace > 80) {
            restore(player)
            return
        }
        if (ticksSincePlace < 3) return
        if (!(player.onGround() || player.isInWater)) return

        val hand = if (usedOffhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        val stack = if (usedOffhand) player.offhandItem else player.inventory.getItem(player.inventory.selectedSlot)

        // an empty bucket means the water is still there: pick it up
        if (stack.item == Items.BUCKET && pickupTries < 4) {
            player.xRot = 90f
            gm.useItem(player, hand)
            pickupTries++
        } else {
            restore(player)
        }
    }

    private fun restore(player: LocalPlayer) {
        if (!usedOffhand) player.inventory.selectedSlot = savedSlot
        player.xRot = savedPitch
        phase = Phase.IDLE
    }

    /** blocks between your feet and the ground, or null if there is water/lava/a soft block or nothing close */
    private fun distanceToGround(level: ClientLevel, player: LocalPlayer): Double? {
        val bx = Mth.floor(player.x)
        val bz = Mth.floor(player.z)
        val top = Mth.floor(player.y - 0.001)
        for (k in 0..MAX_SCAN) {
            val pos = BlockPos(bx, top - k, bz)
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty) return null
            if (!state.getCollisionShape(level, pos).isEmpty) {
                if (state.block in SAFE_BLOCKS) return null
                return player.y - (pos.y + 1.0)
            }
        }
        return null
    }
}
