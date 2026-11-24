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
// Replaces the flat app background with a user-chosen image.

private const val BACKGROUND_NAME = "custom_bg"

private val TRANSPARENT_COLORS = setOf(
    "dark_base_background_base",
    "dark_appframe_background_base",
    "gray_7",
)

private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

private val SPOTIFY = Compatibility(
    packageName = "com.spotify.music",
    name = "Spotify",
    appIconColor = 0x1DB954,
    targets = listOf(AppTarget(version = "9.1.94.1929")),
)

private val backgroundResourcePatch = resourcePatch {
    val imagePath by stringOption(
        key = "imagePath",
        title = "Background image",
        description = "Full path to the image to use as the app background (png, jpg or webp).",
        required = true,
    )

    execute {
        val source = File(imagePath!!)
        require(source.isFile) { "Background image not found: $imagePath" }

        val extension = source.extension.lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "Unsupported image type '.$extension'. Use png, jpg or webp."
        }

        val target = get("res/drawable-nodpi/$BACKGROUND_NAME.$extension")
        target.parentFile.mkdirs()
        target.writeBytes(source.readBytes())

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

private val mainActivityOnCreateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/spotify/music/SpotifyMainActivity;" &&
            method.name == "onCreate"
    }
}

@Suppress("unused")
val customBackgroundPatch = bytecodePatch(
    name = "Custom background",
    description = "Replaces the flat app background with an image you choose.",
) {
    dependsOn(backgroundResourcePatch)

    compatibleWith(SPOTIFY)

    execute {
        // onCreate: v0..v9 locals, p0 = this (v10), p1 = Bundle (v11).
        // At index 0 nothing is written yet, so v0..v4 are free.
        mainActivityOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                move-result-object v0

                invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v1

                const-string v2, "$BACKGROUND_NAME"
                const-string v3, "drawable"

                invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v4

                invoke-virtual {v1, v2, v3, v4}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v1

                if-eqz v1, :skip_custom_background
                invoke-virtual {v0, v1}, Landroid/view/Window;->setBackgroundDrawableResource(I)V
                :skip_custom_background
            """,
        )
    }
}
