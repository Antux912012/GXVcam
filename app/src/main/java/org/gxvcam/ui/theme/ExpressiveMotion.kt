package org.gxvcam.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * Material 3 Expressive & Google Camera Motion Tokens
 *
 * Implements high-fidelity spring dynamics and emphasized deceleration curves
 * aligned with Google's latest Material Design 3 Expressive specifications.
 */
object GXVMotion {
    // Easing curves from Material 3 Expressive Motion
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerateEasing: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val StandardDecelerateEasing: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    // Bouncy Spatial Springs (tactile overshoot for pills, shutters, and dials)
    val spatialBouncy: SpringSpec<Float> = spring(
        dampingRatio = 0.68f,
        stiffness = 380f
    )

    val spatialBouncyDp: SpringSpec<Dp> = spring(
        dampingRatio = 0.68f,
        stiffness = 380f
    )

    // Smooth Spatial Springs (clean tracking without noticeable overshoot)
    val spatialSmooth: SpringSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = 450f
    )

    val spatialSmoothDp: SpringSpec<Dp> = spring(
        dampingRatio = 0.85f,
        stiffness = 450f
    )

    // Expressive Mode Carousel & Lens Pill Morphing Spring
    val pillMorphSpring: SpringSpec<Dp> = spring(
        dampingRatio = 0.72f,
        stiffness = 400f
    )

    // Shutter Press Tactile Response
    val shutterPressSpring: SpringSpec<Float> = spring(
        dampingRatio = 0.62f,
        stiffness = 500f
    )

    // Fast Micro-interactions & Color Blends
    val fastEffects: TweenSpec<Float> = tween(
        durationMillis = 180,
        easing = EmphasizedDecelerateEasing
    )

    val enterEmphasized: TweenSpec<Float> = tween(
        durationMillis = 350,
        easing = EmphasizedDecelerateEasing
    )

    val exitEmphasized: TweenSpec<Float> = tween(
        durationMillis = 200,
        easing = EmphasizedAccelerateEasing
    )

    // Generic spring helpers
    fun <T> spatialBouncy(): SpringSpec<T> = spring(
        dampingRatio = 0.68f,
        stiffness = 380f
    )

    fun <T> spatialSmooth(): SpringSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 450f
    )

    fun <T> pillMorph(): SpringSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = 400f
    )

    fun <T> fastEffects(): TweenSpec<T> = tween(
        durationMillis = 180,
        easing = EmphasizedDecelerateEasing
    )
}
