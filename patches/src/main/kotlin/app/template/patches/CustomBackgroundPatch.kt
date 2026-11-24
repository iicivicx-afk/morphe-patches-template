package app.template.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.w3c.dom.Element
import java.io.File

// Spotify 9.1.94.1929 (com.spotify.music)
// Replaces the flat app background with an image or animated GIF, with optional dimming.
//
// The visible patch is the resource patch (so its options show in Manager).
// The bytecode hook is a hidden dependency. It finds two raw files this patch adds, by NAME:
//   raw/custom_bg       the image or GIF
//   raw/custom_bg_dim   one byte: the gray level (0-255) used to dim the background
// Raw FILES are used (not new values entries) because the resource encoder rejects
// brand-new entries in values XML ("Undefined entry name").
// Needs Android 9+ (ImageDecoder); on older versions the background is left unchanged.

private const val BACKGROUND_NAME = "custom_bg"
private const val DIM_NAME = "custom_bg_dim"

private val TRANSPARENT_COLORS = setOf(
    "dark_base_background_base",
    "dark_appframe_background_base",
    "gray_7",
)

private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

private val SPOTIFY = Compatibility(
    packageName = "com.spotify.music",
    name = "Spotify",
    appIconColor = 0x1DB954,
    targets = listOf(AppTarget(version = "9.1.94.1929")),
)

private val mainActivityOnCreateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/spotify/music/SpotifyMainActivity;" &&
            method.name == "onCreate"
    }
}

private val backgroundHookPatch = bytecodePatch {
    execute {
        // onCreate: v0..v9 locals, p0 = this (v10), p1 = Bundle (v11).
        // At index 0 nothing is written yet, so v0..v7 are free.
        mainActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                move-result-object v0

                invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1

                invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v2

                # ImageDecoder needs Android 9 (API 28)
                sget v3, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                const/16 v4, 0x1c
                if-lt v3, v4, :custom_bg_done

                # v3 = id of raw/custom_bg
                const-string v3, "$BACKGROUND_NAME"
                const-string v4, "raw"
                invoke-virtual {v1, v3, v4, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v3
                if-eqz v3, :custom_bg_done

                # v4 = id of raw/custom_bg_dim
                const-string v4, "$DIM_NAME"
                const-string v5, "raw"
                invoke-virtual {v1, v4, v5, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v4
                if-eqz v4, :custom_bg_done

                # v5 = gray level read from the one-byte file
                invoke-virtual {v1, v4}, Landroid/content/res/Resources;->openRawResource(I)Ljava/io/InputStream;
                move-result-object v4
                invoke-virtual {v4}, Ljava/io/InputStream;->read()I
                move-result v5
                invoke-virtual {v4}, Ljava/io/InputStream;->close()V

                # v7 = 0xFF000000 | gray<<16 | gray<<8 | gray
                shl-int/lit8 v6, v5, 0x8
                or-int/2addr v6, v5
                shl-int/lit8 v7, v6, 0x8
                or-int/2addr v7, v5
                const/high16 v6, -0x1000000
                or-int/2addr v7, v6

                # decode the image or GIF
                invoke-static {v1, v3}, Landroid/graphics/ImageDecoder;->createSource(Landroid/content/res/Resources;I)Landroid/graphics/ImageDecoder${'$'}Source;
                move-result-object v3
                invoke-static {v3}, Landroid/graphics/ImageDecoder;->decodeDrawable(Landroid/graphics/ImageDecoder${'$'}Source;)Landroid/graphics/drawable/Drawable;
                move-result-object v3

                # dim it
                sget-object v4, Landroid/graphics/PorterDuff${'$'}Mode;->MULTIPLY:Landroid/graphics/PorterDuff${'$'}Mode;
                invoke-virtual {v3, v7, v4}, Landroid/graphics/drawable/Drawable;->setColorFilter(ILandroid/graphics/PorterDuff${'$'}Mode;)V

                # animated GIF: loop forever
                instance-of v4, v3, Landroid/graphics/drawable/AnimatedImageDrawable;
                if-eqz v4, :custom_bg_set
                check-cast v3, Landroid/graphics/drawable/AnimatedImageDrawable;
                const/4 v4, -0x1
                invoke-virtual {v3, v4}, Landroid/graphics/drawable/AnimatedImageDrawable;->setRepeatCount(I)V
                invoke-virtual {v3}, Landroid/graphics/drawable/AnimatedImageDrawable;->start()V

                :custom_bg_set
                invoke-virtual {v0, v3}, Landroid/view/Window;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

                :custom_bg_done
            """,
        )
    }
}

@Suppress("unused")
val customBackgroundPatch = resourcePatch(
    name = "Custom background",
    description = "Replaces the flat app background with an image or animated GIF you choose.",
) {
    dependsOn(backgroundHookPatch)

    compatibleWith(SPOTIFY)

    val imagePath by stringOption(
        key = "imagePath",
        title = "Background image or GIF",
        description = "Full path to the file to use as the app background (png, jpg, webp or gif).",
        required = true,
    )

    val dimPercent by stringOption(
        key = "dimPercent",
        default = "25",
        title = "Dim amount (0-90)",
        description = "How much to darken the background so text stays readable. 0 = no dimming.",
        required = false,
    )

    execute {
        val source = File(imagePath!!)
        require(source.isFile) { "Background file not found: $imagePath" }

        val extension = source.extension.lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "Unsupported file type '.$extension'. Use png, jpg, webp or gif."
        }

        val bytes = source.readBytes()

        if (extension == "gif") {
            require(bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF") {
                "The file is named .gif but isn't a valid GIF."
            }
        }

        // The image or GIF, as a raw file (resource name = file name without extension).
        val imageFile = get("res/raw/$BACKGROUND_NAME.$extension")
        imageFile.parentFile.mkdirs()
        imageFile.writeBytes(bytes)

        // Dimming: one byte holding the gray level the background is multiplied by.
        // 25% dim -> 191 (0xBF).
        val dim = (dimPercent?.trim()?.toIntOrNull() ?: 25).coerceIn(0, 90)
        val gray = 255 * (100 - dim) / 100
        val dimFile = get("res/raw/$DIM_NAME.bin")
        dimFile.parentFile.mkdirs()
        dimFile.writeBytes(byteArrayOf(gray.toByte()))

        // Make the flat background colors transparent (edits existing entries only).
        var replaced = 0
        document("res/values/colors.xml").use { document ->
            val colors = document.getElementsByTagName("color")
            for (i in 0 until colors.length) {
                val element = colors.item(i) as Element
                if (element.getAttribute("name") in TRANSPARENT_COLORS) {
                    element.textContent = "#00000000"
                    replaced++
                }
            }
        }

        check(replaced == TRANSPARENT_COLORS.size) {
            "Expected to change ${TRANSPARENT_COLORS.size} colors but changed $replaced."
        }
    }
}
