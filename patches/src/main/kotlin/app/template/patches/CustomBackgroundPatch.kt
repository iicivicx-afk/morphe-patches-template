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
// The bytecode hook is a hidden dependency. It finds the files this patch adds by NAME:
//   drawable/custom_bg      static image
//   raw/custom_bg_gif       animated GIF (Android 9+ only)
//   color/custom_bg_tint    gray multiply tint that dims the background

private const val BACKGROUND_NAME = "custom_bg"
private const val GIF_NAME = "custom_bg_gif"
private const val TINT_NAME = "custom_bg_tint"

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

                # v3 = dim tint color
                const-string v3, "$TINT_NAME"
                const-string v4, "color"
                invoke-virtual {v1, v3, v4, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v3
                if-eqz v3, :custom_bg_done
                invoke-virtual {v1, v3}, Landroid/content/res/Resources;->getColor(I)I
                move-result v3

                # static image?
                const-string v5, "$BACKGROUND_NAME"
                const-string v6, "drawable"
                invoke-virtual {v1, v5, v6, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v5
                if-eqz v5, :custom_bg_try_gif

                invoke-virtual {v1, v5}, Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;
                move-result-object v5
                invoke-virtual {v5}, Landroid/graphics/drawable/Drawable;->mutate()Landroid/graphics/drawable/Drawable;
                move-result-object v5
                sget-object v6, Landroid/graphics/PorterDuff${'$'}Mode;->MULTIPLY:Landroid/graphics/PorterDuff${'$'}Mode;
                invoke-virtual {v5, v3, v6}, Landroid/graphics/drawable/Drawable;->setColorFilter(ILandroid/graphics/PorterDuff${'$'}Mode;)V
                invoke-virtual {v0, v5}, Landroid/view/Window;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V
                goto :custom_bg_done

                # animated GIF? (needs ImageDecoder, Android 9 / API 28+)
                :custom_bg_try_gif
                const-string v5, "$GIF_NAME"
                const-string v6, "raw"
                invoke-virtual {v1, v5, v6, v2}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v5
                if-eqz v5, :custom_bg_done

                sget v6, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                const/16 v7, 0x1c
                if-lt v6, v7, :custom_bg_done

                invoke-static {v1, v5}, Landroid/graphics/ImageDecoder;->createSource(Landroid/content/res/Resources;I)Landroid/graphics/ImageDecoder${'$'}Source;
                move-result-object v5
                invoke-static {v5}, Landroid/graphics/ImageDecoder;->decodeDrawable(Landroid/graphics/ImageDecoder${'$'}Source;)Landroid/graphics/drawable/Drawable;
                move-result-object v5

                sget-object v6, Landroid/graphics/PorterDuff${'$'}Mode;->MULTIPLY:Landroid/graphics/PorterDuff${'$'}Mode;
                invoke-virtual {v5, v3, v6}, Landroid/graphics/drawable/Drawable;->setColorFilter(ILandroid/graphics/PorterDuff${'$'}Mode;)V

                instance-of v7, v5, Landroid/graphics/drawable/AnimatedImageDrawable;
                if-eqz v7, :custom_bg_set_gif
                check-cast v5, Landroid/graphics/drawable/AnimatedImageDrawable;
                const/4 v7, -0x1
                invoke-virtual {v5, v7}, Landroid/graphics/drawable/AnimatedImageDrawable;->setRepeatCount(I)V
                invoke-virtual {v5}, Landroid/graphics/drawable/AnimatedImageDrawable;->start()V

                :custom_bg_set_gif
                invoke-virtual {v0, v5}, Landroid/view/Window;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V

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
            val target = get("res/raw/$GIF_NAME.gif")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        } else {
            val target = get("res/drawable-nodpi/$BACKGROUND_NAME.$extension")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }

        // Dimming: multiply the background by a gray. 25% dim -> #FFBFBFBF.
        val dim = (dimPercent?.trim()?.toIntOrNull() ?: 25).coerceIn(0, 90)
        val channel = 255 * (100 - dim) / 100
        val hex = "%02x".format(channel)
        val tintFile = get("res/values/$TINT_NAME.xml")
        tintFile.parentFile.mkdirs()
        tintFile.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<resources>\n" +
                "    <color name=\"$TINT_NAME\">#ff$hex$hex$hex</color>\n" +
                "</resources>\n",
        )

        // Make the flat background colors transparent.
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
