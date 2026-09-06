package com.molotov.clender.data.settings

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/** Call on an IO worker. Owns only normalized backgrounds and its atomic configuration. */
class BackgroundStore(private val directory: File) {
    private val settings get() = File(directory, "settings.json")

    fun read(): BackgroundSelection = try {
        val bytes = settings.inputStream().use { readBounded(it, MAX_SETTINGS_BYTES) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        BackgroundSelection(
            fileName = json.optString("file").takeIf(::isOwnedName),
            strength = BackgroundPolicy.strength(json.opt("strength"))
        )
    } catch (_: IOException) {
        BackgroundSelection()
    } catch (_: JSONException) {
        BackgroundSelection()
    } catch (_: IllegalArgumentException) {
        BackgroundSelection()
    }

    fun importImage(open: () -> InputStream?): BackgroundSelection {
        val bytes = requireNotNull(open()) { "Image unavailable" }.use { readBounded(it) }
        val bitmap = requireNotNull(BackgroundImageDecoder.decode(bytes)) { "Unsupported image" }
        val previous = read()
        check(directory.isDirectory || directory.mkdirs()) { "Background directory unavailable" }
        val name = "background-${UUID.randomUUID().toString().replace("-", "")}.png"
        val imageFile = File(directory, name)
        try {
            imageFile.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
            }
            val updated = previous.copy(fileName = name)
            write(updated)
            deleteOwned(previous.fileName)
            return updated
        } catch (error: IOException) {
            imageFile.delete()
            throw error
        } catch (error: IllegalStateException) {
            imageFile.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    fun saveStrength(value: Int): BackgroundSelection = read()
        .copy(strength = BackgroundPolicy.strength(value)).also(::write)

    fun remove(): BackgroundSelection {
        val previous = read()
        val updated = previous.copy(fileName = null)
        write(updated)
        deleteOwned(previous.fileName)
        return updated
    }

    fun loadBitmap(selection: BackgroundSelection): Bitmap? {
        val name = selection.fileName?.takeIf(::isOwnedName) ?: return null
        return try {
            File(directory, name).inputStream().use {
                BackgroundImageDecoder.decode(readBounded(it))
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun write(selection: BackgroundSelection) {
        check(directory.isDirectory || directory.mkdirs()) { "Background directory unavailable" }
        val data = JSONObject().put("file", selection.fileName)
            .put("strength", selection.strength).toString().toByteArray(Charsets.UTF_8)
        val pending = File.createTempFile("settings-", ".tmp", directory)
        try {
            pending.outputStream().use { output ->
                output.write(data)
                output.fd.sync()
            }
            Files.move(
                pending.toPath(),
                settings.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            pending.delete()
        }
    }

    private fun deleteOwned(name: String?) {
        name?.takeIf(::isOwnedName)?.let { File(directory, it).delete() }
    }

    private fun isOwnedName(name: String): Boolean = OWNED_NAME.matches(name)

    private fun readBounded(
        input: InputStream,
        limit: Int = BackgroundPolicy.MAX_INPUT_BYTES
    ): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        var count = input.read(buffer)
        while (count != -1) {
            total += count
            require(total <= limit) { "Image too large" }
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        output.toByteArray()
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val MAX_SETTINGS_BYTES = 4096
        val OWNED_NAME = Regex("background-[a-f0-9]{32}\\.png")
    }
}
