package com.onthecrow.onthecrowvpn.connection

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.onthecrow.nimbus.NimbusEffect
import com.onthecrow.nimbus.NimbusLayer
import com.onthecrow.nimbus.NimbusMotion
import com.onthecrow.nimbus.nimbus

/**
 * The two layers Nimbus was tuned against: a cyan ring turning one way and a violet one turning
 * slower the other way. Passed explicitly — the library's own `DefaultLayers` are ~3× spikier
 * (maxPeakHeight 0.42/0.48 vs 0.12/0.2) and look noticeably different.
 */
private val NimbusDefaultLayers: List<NimbusLayer> = listOf(
    NimbusLayer(
        color = Color(0xFF4DD0E1),
        minSpikesCount = 5,
        maxSpikesCount = 9,
        maxPeakHeight = 0.12f,
        alpha = 0.80f,
        spawnSpeed = 0.10f,
        sizeChangeSpeed = 1.20f,
        motionMode = NimbusMotion.RIGID,
        rotationSpeed = 0.20f,
        wobbleAmount = 0.30f,
        wobbleSpeed = 0.50f,
        jitter = 0.12f,
        widthFactor = 1.15f,
    ),
    NimbusLayer(
        color = Color(0xFF7C4DFF),
        minSpikesCount = 4,
        maxSpikesCount = 7,
        maxPeakHeight = 0.20f,
        alpha = 0.75f,
        spawnSpeed = 0.10f,
        sizeChangeSpeed = 1.20f,
        motionMode = NimbusMotion.RIGID,
        rotationSpeed = -0.15f,
        wobbleAmount = 0.30f,
        wobbleSpeed = 0.50f,
        jitter = 0.12f,
        widthFactor = 1.15f,
    ),
)

/**
 * `Modifier.nimbus` preconfigured as in the Nimbus sample.
 *
 * @param active whether the glow is shown; flipping it animates in/out over 700 ms. While false the
 *   node isn't redrawn at all, so a disconnected button costs nothing.
 * @param shape MUST match the shape the button is drawn with, or the spikes won't follow its edge.
 */
internal fun Modifier.nimbusDefaults(
    active: Boolean,
    shape: Shape,
): Modifier = nimbus(
    shape = shape,
    active = active,
    layers = NimbusDefaultLayers,
    sharpness = 0.5f,
    collar = 4.dp,
    effect = NimbusEffect.GLOW,
    effectIntensity = 1f,
    countEasing = FastOutSlowInEasing,
    rotationEasing = LinearEasing,
    activationSpec = tween(700),
)
