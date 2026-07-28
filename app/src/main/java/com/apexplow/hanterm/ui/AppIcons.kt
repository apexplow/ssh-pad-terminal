package com.apexplow.hanterm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AppIcons {
    val Terminal: ImageVector by lazy {
        ImageVector.Builder(
            name = "Terminal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 17f)
            lineTo(10f, 12f)
            lineTo(4f, 7f)
            moveTo(12f, 19f)
            horizontalLineTo(20f)
        }.build()
    }

    val Server: ImageVector by lazy {
        ImageVector.Builder(
            name = "Server",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 4f)
            horizontalLineTo(22f)
            verticalLineTo(10f)
            horizontalLineTo(2f)
            close()
            moveTo(2f, 14f)
            horizontalLineTo(22f)
            verticalLineTo(20f)
            horizontalLineTo(2f)
            close()
            moveTo(6f, 7f)
            horizontalLineTo(6.01f)
            moveTo(6f, 17f)
            horizontalLineTo(6.01f)
        }.build()
    }

    val Port: ImageVector by lazy {
        ImageVector.Builder(
            name = "Port",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            verticalLineTo(6f)
            moveTo(12f, 18f)
            verticalLineTo(22f)
            moveTo(4.93f, 4.93f)
            lineTo(7.76f, 7.76f)
            moveTo(16.24f, 16.24f)
            lineTo(19.07f, 19.07f)
            moveTo(2f, 12f)
            horizontalLineTo(6f)
            moveTo(18f, 12f)
            horizontalLineTo(22f)
        }.build()
    }

    val Key: ImageVector by lazy {
        ImageVector.Builder(
            name = "Key",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 2f)
            lineTo(11.3f, 11.7f)
            moveTo(15.5f, 7.5f)
            lineTo(18f, 10f)
            moveTo(7.5f, 12f)
            arcTo(4.5f, 4.5f, 0f, true, false, 3f, 16.5f)
            arcTo(4.5f, 4.5f, 0f, false, false, 7.5f, 12f)
        }.build()
    }

    val Eye: ImageVector by lazy {
        ImageVector.Builder(
            name = "Eye",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 12f)
            curveTo(5f, 5f, 19f, 5f, 22f, 12f)
            curveTo(19f, 19f, 5f, 19f, 2f, 12f)
            moveTo(12f, 15f)
            arcTo(3f, 3f, 0f, true, true, 12f, 9f)
            arcTo(3f, 3f, 0f, false, true, 12f, 15f)
        }.build()
    }

    val EyeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 1f)
            lineTo(23f, 23f)
            moveTo(10.5f, 10.5f)
            arcTo(3f, 3f, 0f, false, false, 13.5f, 13.5f)
        }.build()
    }

    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Folder",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 19f)
            horizontalLineTo(2f)
            verticalLineTo(5f)
            horizontalLineTo(9f)
            lineTo(11f, 7f)
            horizontalLineTo(22f)
            close()
        }.build()
    }

    val Save: ImageVector by lazy {
        ImageVector.Builder(
            name = "Save",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 21f)
            horizontalLineTo(5f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            lineTo(21f, 8f)
            verticalLineTo(19f)
            moveTo(17f, 21f)
            verticalLineTo(13f)
            horizontalLineTo(7f)
            verticalLineTo(21f)
            moveTo(7f, 3f)
            verticalLineTo(8f)
            horizontalLineTo(15f)
        }.build()
    }

    val Power: ImageVector by lazy {
        ImageVector.Builder(
            name = "Power",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            verticalLineTo(12f)
            moveTo(18.36f, 6.64f)
            arcTo(9f, 9f, 0f, true, true, 5.64f, 6.64f)
        }.build()
    }
}
