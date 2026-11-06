package org.gxvcam.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

object GXVMotion {
    val spatialBouncy: SpringSpec<Float> = spring(
        dampingRatio = 0.65f,
        stiffness = 350f
    )

    val spatialBouncyDp: SpringSpec<Dp> = spring(
        dampingRatio = 0.65f,
        stiffness = 350f
    )

    val spatialSmooth: SpringSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = 500f
    )

    val fastEffects: TweenSpec<Float> = tween(durationMillis = 150)

    fun <T> spatialBouncy(): SpringSpec<T> = spring(
        dampingRatio = 0.65f,
        stiffness = 350f
    )

    fun <T> spatialSmooth(): SpringSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = 500f
    )

    fun <T> fastEffects(): TweenSpec<T> = tween(durationMillis = 150)
}
