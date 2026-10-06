package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Keep the selected bytes intact: GIF and animated WebP must survive app updates. */
class Phone10AvatarStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone10-avatar.original"))
    private val legacy = File(context.noBackupFilesDir, "phone10-avatar.png")
    private val prefs = context.getSharedPreferences("avatar-appearance", Context.MODE_PRIVATE)

    fun source(): File? {
        if (prefs.getBoolean("default_avatar", false)) return null
        return file.baseFile.takeIf { it.isFile } ?: legacy.takeIf { it.isFile }
    }

    fun restoreDefault() {
        check(prefs.edit().putBoolean("default_avatar", true).commit()) { "无法保存默认头像。" }
    }

    fun import(uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("图片超过 20 MB，请选较小的文件。")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("无法打开所选图片。")
        validate(bytes)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        check(prefs.edit().putBoolean("default_avatar", false).commit()) { "无法启用新头像。" }
    }

    private fun validate(bytes: ByteArray) {
        val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        if (!gif && !webp && !png && !jpeg) throw IOException("仅支持 PNG、JPEG、GIF 和 WebP 图片。")
        if (webp && Build.VERSION.SDK_INT < 28 && bytes.indexOfAnimatedWebpChunk()) {
            throw IOException("这台手机的系统不支持动态 WebP，请选 GIF。")
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 512) decoder.setTargetSize(
                        maxOf(1, info.size.width * 512 / longest),
                        maxOf(1, info.size.height * 512 / longest))
                }
                (drawable as? Animatable)?.stop()
            } catch (_: Exception) { throw IOException("图片损坏或无法解码，请换一张。") }
        } else if (gif) {
            if (Movie.decodeByteArray(bytes, 0, bytes.size) == null) throw IOException("GIF 图片无法解码。")
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片无法解码。")
        }
    }

    private fun ByteArray.indexOfAnimatedWebpChunk(): Boolean {
        var offset = 12
        while (offset + 8 <= size) {
            val type = String(this, offset, 4, Charsets.US_ASCII)
            val length = (this[offset + 4].toInt() and 255) or
                ((this[offset + 5].toInt() and 255) shl 8) or
                ((this[offset + 6].toInt() and 255) shl 16) or
                ((this[offset + 7].toInt() and 255) shl 24)
            if (type == "ANIM" || type == "ANMF") return true
            if (type == "VP8X" && length >= 1 && offset + 8 < size && this[offset + 8].toInt() and 2 != 0) return true
            if (length < 0 || offset + 8L + length > size) break
            offset += 8 + length + (length and 1)
        }
        return false
    }

    companion object { private const val MAX_BYTES = 20 * 1024 * 1024 }
}

/** One decoder for both the settings preview and the existing floating view. */
class Phone10AvatarLoader(context: Context) {
    private val app = context.applicationContext
    private val loader = ImageLoader.Builder(app).components {
        if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
        else add(GifDecoder.Factory())
    }.build()

    suspend fun load(source: File?): Drawable? {
        if (source == null) return null
        val result = loader.execute(ImageRequest.Builder(app).data(source).size(512)
            .allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED).build())
        return (result as? SuccessResult)?.drawable ?: throw IOException("头像无法显示，请重新选择图片。")
    }
}