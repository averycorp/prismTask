package com.averycorp.prismtask.ui.theme

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for the WindowSizeClass infra introduced in the D11
 * finish bundle (Item 1, path 2). Compose-runtime-dependent behavior of
 * `Modifier.expandedWidthCap()` is exercised by Compose preview tests in
 * `androidTest/`; these tests verify the underlying width-class
 * classification is what we expect for the three breakpoints we care
 * about: phone (Compact), foldable-unfolded (Medium), tablet (Expanded).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ExpandedWidthCapTest {
    @Test
    fun phonePortrait360dp_classifiesAsCompact() {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 800.dp))
        assertEquals(WindowWidthSizeClass.Compact, sizeClass.widthSizeClass)
    }

    @Test
    fun smallTablet600dp_classifiesAsMedium() {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(600.dp, 1024.dp))
        assertEquals(WindowWidthSizeClass.Medium, sizeClass.widthSizeClass)
    }

    @Test
    fun tabletLandscape1280dp_classifiesAsExpanded() {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp))
        assertEquals(WindowWidthSizeClass.Expanded, sizeClass.widthSizeClass)
    }

    @Test
    fun phoneHeight800dp_classifiesAsMediumHeight() {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 800.dp))
        // Sanity: phone portrait reports a Medium height per Material 3
        // breakpoints; expandedWidthCap depends only on widthSizeClass.
        assertEquals(WindowHeightSizeClass.Medium, sizeClass.heightSizeClass)
    }
}
