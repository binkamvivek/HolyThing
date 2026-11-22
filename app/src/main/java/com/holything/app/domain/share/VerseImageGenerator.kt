package com.holything.app.domain.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.holything.app.domain.model.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ShareSize(val width: Int, val height: Int, val label: String) {
    STORY(1080, 1920, "Story (9:16)"),
    SQUARE(1080, 1080, "Square (1:1)")
}

enum class ShareTheme(
    val label: String,
    val topColor: Int,
    val bottomColor: Int,
    val textColor: Int,
    val refColor: Int,
    val watermarkColor: Int
) {
    WARM_DAWN(
        label = "Dawn",
        topColor = Color.rgb(253, 246, 240),
        bottomColor = Color.rgb(243, 226, 214),
        textColor = Color.rgb(44, 34, 30),
        refColor = Color.rgb(105, 87, 80),
        watermarkColor = Color.argb(160, 138, 118, 110)
    ),
    PARCHMENT(
        label = "Parchment",
        topColor = Color.rgb(251, 248, 241),
        bottomColor = Color.rgb(238, 232, 217),
        textColor = Color.rgb(38, 33, 27),
        refColor = Color.rgb(92, 82, 70),
        watermarkColor = Color.argb(160, 120, 110, 98)
    ),
    MIDNIGHT(
        label = "Midnight",
        topColor = Color.rgb(20, 30, 48),
        bottomColor = Color.rgb(36, 59, 85),
        textColor = Color.rgb(248, 246, 240),
        refColor = Color.rgb(175, 188, 205),
        watermarkColor = Color.argb(160, 140, 155, 175)
    ),
    SAGE(
        label = "Sage",
        topColor = Color.rgb(30, 52, 42),
        bottomColor = Color.rgb(47, 79, 62),
        textColor = Color.rgb(245, 247, 242),
        refColor = Color.rgb(180, 198, 185),
        watermarkColor = Color.argb(160, 150, 172, 158)
    )
}

object VerseImageGenerator {

    suspend fun generateVerseImage(
        context: Context,
        card: Card,
        theme: ShareTheme = ShareTheme.WARM_DAWN,
        size: ShareSize = ShareSize.STORY
    ): Uri = withContext(Dispatchers.IO) {
        val width = size.width
        val height = size.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw Background Gradient
        val gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            theme.topColor, theme.bottomColor,
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Margins & safe area
        val horizontalMargin = 120
        val maxTextWidth = width - (horizontalMargin * 2)

        // 2. Setup Typography
        val verseTextSize = if (size == ShareSize.STORY) {
            if (card.verseText.length > 250) 48f else 54f
        } else {
            if (card.verseText.length > 250) 42f else 48f
        }

        val versePaint = TextPaint().apply {
            color = theme.textColor
            textSize = verseTextSize
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val refPaint = TextPaint().apply {
            color = theme.refColor
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            isAntiAlias = true
        }

        val watermarkPaint = TextPaint().apply {
            color = theme.watermarkColor
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // 3. Layout Verse Text
        val verseContent = "“${card.verseText.trim()}”"
        val verseLayout = StaticLayout.Builder.obtain(
            verseContent, 0, verseContent.length, versePaint, maxTextWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(16f, 1.25f)
            .setIncludePad(false)
            .build()

        val refContent = "— ${card.verseRef} (${card.translation})"
        val refLayout = StaticLayout.Builder.obtain(
            refContent, 0, refContent.length, refPaint, maxTextWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        // 4. Center vertical placement
        val totalContentHeight = verseLayout.height + 40 + refLayout.height
        val startY = (height - totalContentHeight) / 2f

        // Draw Verse
        canvas.save()
        canvas.translate(horizontalMargin.toFloat(), startY)
        verseLayout.draw(canvas)
        canvas.restore()

        // Draw Reference
        canvas.save()
        canvas.translate(horizontalMargin.toFloat(), startY + verseLayout.height + 40f)
        refLayout.draw(canvas)
        canvas.restore()

        // 5. Draw Discreet "HolyThing" watermark at bottom
        val watermarkY = height - 90f
        canvas.drawText("HolyThing", width / 2f, watermarkY, watermarkPaint)

        // 6. Save bitmap to cache directory
        val cacheFolder = File(context.cacheDir, "shared_verses").apply { mkdirs() }
        val imageFile = File(cacheFolder, "holything_${card.id}_${size.name.lowercase()}.png")
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }
}
