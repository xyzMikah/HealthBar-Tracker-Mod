package com.mk.healthbar.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(AvatarRenderer.class)
public class PlayerRendererMixin {

    private static final Identifier HEART_FULL       = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_HALF       = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier HEART_FULL_BLINK = Identifier.withDefaultNamespace("hud/heart/full_blinking");
    private static final Identifier HEART_HALF_BLINK = Identifier.withDefaultNamespace("hud/heart/half_blinking");

    private static final int HEART_PX = 9;
    private static final float HEART_SCALE = 0.014f; // smaller than nametag's 0.025

    // Tracks last known health + last damage tick per player, so blinking only happens on real damage.
    private static final Map<Integer, float[]> lastHealthData = new HashMap<>(); // [lastHealth, lastDamageTick]

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void healthbar$renderHearts(
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.getId() == state.id) return;
        if (state.nameTagAttachment == null) return;

        Player target = null;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.getId() == state.id) { target = p; break; }
        }
        if (target == null || target.isSpectator()) return;

        // Abilities aren't synced for remote players, so check their gamemode via the tab-list info instead.
        var playerInfo = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(target.getUUID()) : null;
        if (playerInfo != null && playerInfo.getGameMode() == net.minecraft.world.level.GameType.CREATIVE) return;

        float health = Math.max(0, Math.min(target.getHealth(), target.getMaxHealth()));
        float maxHealth = Math.max(1, target.getMaxHealth());
        if (health <= 0) return;

        int currentHealthInt = (int) Math.ceil(health); // matches vanilla HUD rounding

        int heartCount = Math.max(1, Math.min((int) Math.ceil(maxHealth / 2f), 10));

        long time = mc.level.getGameTime();

        // Track real damage for this player to gate blinking correctly.
        float[] data = lastHealthData.computeIfAbsent(state.id, k -> new float[]{health, -1000});
        if (health < data[0]) {
            data[1] = time; // damage taken this tick
        }
        data[0] = health;
        boolean recentlyDamaged = (time - data[1]) < 20; // ~1 second window
        boolean blink = recentlyDamaged && ((time / 3) % 2 == 0);

        AtlasManager atlasManager = mc.getAtlasManager();

        poseStack.pushPose();
        // Move to the actual head/nametag attachment point, then a bit further up.
        poseStack.translate(state.nameTagAttachment.x, state.nameTagAttachment.y + 0.28, state.nameTagAttachment.z);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(-HEART_SCALE, -HEART_SCALE, HEART_SCALE);

        int totalWidth = heartCount * HEART_PX;

        for (int i = 0; i < heartCount; i++) {
            int halves = i * 2;
            Identifier spriteId;
            if (halves + 2 <= currentHealthInt) {
                spriteId = blink ? HEART_FULL_BLINK : HEART_FULL;
            } else if (halves + 1 <= currentHealthInt) {
                spriteId = blink ? HEART_HALF_BLINK : HEART_HALF;
            } else {
                continue;
            }

            // Mirror horizontally so hearts drain right-to-left instead of left-to-right.
            final int x = (totalWidth / 2) - HEART_PX - (i * HEART_PX);

            TextureAtlasSprite sprite = atlasManager.getAtlasOrThrow(AtlasIds.GUI).getSprite(spriteId);
            RenderType renderType = RenderTypes.entityCutout(sprite.atlasLocation());

            final float u0 = sprite.getU0(), u1 = sprite.getU1();
            final float v0 = sprite.getV0(), v1 = sprite.getV1();

            submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
                var matrix = pose.pose();
                // u0/u1 swapped here to cancel the double horizontal mirror from billboard scale + drain-direction layout.
                buffer.addVertex(matrix, x, 0, 0).setColor(255, 255, 255, 255).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 0, 1);
                buffer.addVertex(matrix, x, HEART_PX, 0).setColor(255, 255, 255, 255).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 0, 1);
                buffer.addVertex(matrix, x + HEART_PX, HEART_PX, 0).setColor(255, 255, 255, 255).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 0, 1);
                buffer.addVertex(matrix, x + HEART_PX, 0, 0).setColor(255, 255, 255, 255).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 0, 1);
            });
        }

        poseStack.popPose();
    }
}