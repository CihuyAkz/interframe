package dev.fallingcloud.interframe;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interframe — AI frame generation for Minecraft.
 *
 * <p>Captures each finished frame and synthesises one or more <em>in-between</em> frames, presenting
 * them between the real ones to raise the perceived frame rate and smooth motion. The synthesiser is
 * layered and degrades gracefully (see {@link dev.fallingcloud.interframe.synth.FrameSynthesizer}):
 * a always-correct cross-blend, a rotational motion-reprojection ("timewarp") that sharpens the common
 * mouse-look case, and an optional neural RIFE backend (ONNX Runtime) for true learned interpolation.
 *
 * <p>Built for the same stack as Foveate: NeoForge 21.1 / MC 1.21.1 with Sodium 0.8 + Iris. It hooks a
 * different pipeline stage than Foveate (frame <em>presentation</em> vs. terrain shading rate), captures
 * the final composited image downstream of Sodium/Iris/Foveate, and registers its settings page through
 * Sodium's config API so it appears in the video settings menu — including when rendered by Reese's
 * Sodium Options. Configuration lives in {@link InterframeConfig}; the GL work is in
 * {@link FrameGenerator}.
 */
@Mod(value = Interframe.MOD_ID, dist = Dist.CLIENT)
public final class Interframe {
    public static final String MOD_ID = "interframe";
    public static final String VERSION = "1.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("Interframe");

    public Interframe() {
        InterframeConfig.get(); // load (or create defaults) eagerly so the file exists on first launch
        LOGGER.info("[Interframe] Initialised.");
    }
}
