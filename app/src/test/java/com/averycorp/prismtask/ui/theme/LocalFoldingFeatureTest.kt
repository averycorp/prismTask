package com.averycorp.prismtask.ui.theme

import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure unit tests for the FoldingFeature infra introduced in F-FOLDABLE-001
 * (F3 closure). Compose-runtime-dependent behavior of
 * [hingeAwareHorizontalPadding] is exercised by Compose preview tooling;
 * these tests verify the [previewFold] helper produces consistent objects
 * across the postures we model in screen previews, and the [FoldingFeature]
 * properties read the way our screen-level branches expect.
 *
 * The CompositionLocal default-null behavior is verified at compile time
 * by the static type `FoldingFeature?` and at runtime by every screen
 * adaptation site (they read `LocalFoldingFeature.current` and rely on
 * the null branch for non-foldable devices, which dominate the user
 * segment).
 */
class LocalFoldingFeatureTest {
    @Test
    fun previewFold_defaults_modelHalfOpenedVerticalHinge() {
        val fold = previewFold()
        assertEquals(FoldingFeature.State.HALF_OPENED, fold.state)
        assertEquals(FoldingFeature.Orientation.VERTICAL, fold.orientation)
        assertEquals(true, fold.isSeparating)
        assertEquals(FoldingFeature.OcclusionType.NONE, fold.occlusionType)
        assertNotNull(fold.bounds)
    }

    @Test
    fun previewFold_flat_modelsClosedDevice() {
        val fold = previewFold(state = FoldingFeature.State.FLAT)
        assertEquals(FoldingFeature.State.FLAT, fold.state)
        assertEquals(false, fold.isSeparating)
    }

    @Test
    fun previewFold_horizontal_modelsTabletopPosture() {
        val fold = previewFold(orientation = FoldingFeature.Orientation.HORIZONTAL)
        assertEquals(FoldingFeature.Orientation.HORIZONTAL, fold.orientation)
        assertEquals(FoldingFeature.State.HALF_OPENED, fold.state)
    }

    @Test
    fun previewFold_propertyAccessIsStable() {
        val fold = previewFold()
        val firstState = fold.state
        val secondState = fold.state
        assertEquals(firstState, secondState)
        assertEquals(fold.orientation, fold.orientation)
    }

    @Test
    fun previewFold_passesBoundsThrough() {
        // Reference equality: android.jar's Rect is unmocked in plain JVM
        // unit tests, so Rect.equals returns the default Boolean (false)
        // even for the same reference. Identity is the semantic we want
        // anyway — previewFold should hand the same Rect back, not a copy.
        val customBounds = android.graphics.Rect(100, 200, 300, 400)
        val fold = previewFold(bounds = customBounds)
        assertSame(customBounds, fold.bounds)
    }
}
