package com.example.hypericons

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import rikka.shizuku.Shizuku
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Runs shell commands as the ADB shell user through Shizuku (no PC needed). */
object Shell {
    class Result(val code: Int, val text: String, val err: String)

    private fun newProcess(cmd: String): Process {
        // Shizuku.newProcess is private in API 13+, so call it by reflection.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun exec(cmd: String, stdin: InputStream? = null, stdout: OutputStream? = null): Result {
        val p = newProcess(cmd)
        val buf = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val sink = stdout ?: buf
        val t1 = Thread { try { p.inputStream.copyTo(sink) } catch (_: Exception) {} }
        val t2 = Thread { try { p.errorStream.copyTo(err) } catch (_: Exception) {} }
        t1.start(); t2.start()
        var werr: Exception? = null
        try {
            if (stdin != null) p.outputStream.use { stdin.copyTo(it) } else p.outputStream.close()
        } catch (e: Exception) { werr = e }
        val code = p.waitFor()
        t1.join(); t2.join()
        if (werr != null) throw IOException("shell write failed: ${werr.message}")
        return Result(code, String(buf.toByteArray()), String(err.toByteArray()))
    }
}

object IconPack {
    /** package name -> drawable name, parsed from the icon pack's appfilter.xml */
    fun readAppFilter(res: Resources, pkg: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        val rawId = if (xmlId == 0) res.getIdentifier("appfilter", "raw", pkg) else 0
        var streamToClose: InputStream? = null
        try {
            val parser: XmlPullParser = when {
                xmlId != 0 -> res.getXml(xmlId)
                rawId != 0 -> {
                    streamToClose = res.openRawResource(rawId)
                    XmlPullParserFactory.newInstance().newPullParser().apply {
                        setInput(streamToClose, null)
                    }
                }
                else -> {
                    streamToClose = res.assets.open("appfilter.xml")
                    XmlPullParserFactory.newInstance().newPullParser().apply {
                        setInput(streamToClose, null)
                    }
                }
            }
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val dr = parser.getAttributeValue(null, "drawable")
                    if (comp != null && dr != null && comp.startsWith("ComponentInfo{")) {
                        val p = comp.substringAfter("{").substringBefore("/")
                        if (p.isNotEmpty() && !out.containsKey(p)) out[p] = dr
                    }
                }
                ev = parser.next()
            }
        } finally {
            try { streamToClose?.close() } catch (_: Exception) {}
        }
        return out
    }
}

object PngUtil {
    fun render(d: Drawable): Bitmap {
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 512
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 512
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    /** Crop transparent padding, then fit the artwork into a size x size canvas (like the guide's resize script). */
    fun trimAndFit(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val scale = size.toFloat() / maxOf(cw, ch)
        val dw = cw * scale
        val dh = ch * scale
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(out).drawBitmap(
            src,
            Rect(minX, minY, maxX + 1, maxY + 1),
            RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f),
            paint
        )
        return out
    }

    fun encode(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, o)
        return addColorChunks(o.toByteArray())
    }

    /** The guide's script adds sRGB + sBIT chunks right after IHDR. */
    private fun addColorChunks(png: ByteArray): ByteArray {
        var pos = 8
        var hasSrgb = false; var hasSbit = false; var hasIccp = false
        while (pos + 12 <= png.size) {
            val len = ((png[pos].toInt() and 0xFF) shl 24) or ((png[pos + 1].toInt() and 0xFF) shl 16) or
                ((png[pos + 2].toInt() and 0xFF) shl 8) or (png[pos + 3].toInt() and 0xFF)
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            when (type) { "sRGB" -> hasSrgb = true; "sBIT" -> hasSbit = true; "iCCP" -> hasIccp = true }
            if (type == "IDAT" || type == "IEND") break
            pos += 12 + len
        }
        val extra = ByteArrayOutputStream()
        if (!hasSrgb && !hasIccp) extra.write(chunk("sRGB", byteArrayOf(0)))
        if (!hasSbit) extra.write(chunk("sBIT", byteArrayOf(8, 8, 8, 8)))
        if (extra.size() == 0) return png
        return png.copyOfRange(0, 33) + extra.toByteArray() + png.copyOfRange(33, png.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val tb = type.toByteArray(Charsets.ISO_8859_1)
        val bb = ByteBuffer.allocate(12 + data.size)
        bb.putInt(data.size); bb.put(tb); bb.put(data)
        val crc = CRC32(); crc.update(tb); crc.update(data)
        bb.putInt(crc.value.toInt())
        return bb.array()
    }
}

object Pipeline {
    private const val THEME_MGR = "com.android.thememanager"
    private val pkgPng = Regex("^res/[^/]+/([A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+)\\.png\$")

    fun run(
        ctx: Context, pack: String, iconsDir: String, trim: Boolean, keepExtras: Boolean,
        manualSize: Int?, log: (String) -> Unit
    ) {
        if (pack.isEmpty()) error("Pick an icon pack first")
        val work = File(ctx.cacheDir, "work")
        work.deleteRecursively(); work.mkdirs()

        // 1. Find the newest theme file (the dummy theme) and pull it
        val ls = Shell.exec("ls -t ${Shell.q(iconsDir)}")
        if (ls.code != 0) error("Can't read $iconsDir: ${ls.err.trim()}")
        val names = ls.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val mrcName = names.firstOrNull { it.endsWith(".mrc") } ?: names.firstOrNull()
            ?: error("No theme file in $iconsDir. Install a dummy theme from the Themes store first.")
        val mrcPath = "$iconsDir/$mrcName"
        log("Using theme file: $mrcName")

        val orig = File(work, "orig.mrc")
        FileOutputStream(orig).use { os ->
            val r = Shell.exec("cat ${Shell.q(mrcPath)}", stdout = os)
            if (r.code != 0) error("Read failed: ${r.err.trim()}")
        }
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null) {
            val bk = File(ext, "backup_$mrcName")
            if (!bk.exists()) { orig.copyTo(bk); log("Backup saved: ${bk.name}") }
        }

        // 2. Build the new theme file
        val newFile = File(work, "new.mrc")
        val res = ctx.packageManager.getResourcesForApplication(pack)
        val map = IconPack.readAppFilter(res, pack)
        log("Icon pack maps ${map.size} apps")

        ZipFile(orig).use { zf ->
            val entries = zf.entries().toList()
            if (entries.none { it.name.endsWith("transform_config.xml") })
                error("That theme has no transform_config.xml. Install another icon theme as the dummy.")
            val sample = entries.firstOrNull { pkgPng.matches(it.name) }
            val iconDir = sample?.name?.substringBeforeLast('/') ?: "res/drawable-xxhdpi"

            var size = manualSize ?: 0
            if (trim && size <= 0) {
                if (sample == null) error("Can't detect the theme's icon size. Type it in manually.")
                val bytes = zf.getInputStream(sample).use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                size = o.outWidth
                if (size <= 0) error("Couldn't read the theme icon size. Type it in manually.")
            }
            if (trim) log("Resizing icons to ${size}px (theme size)")

            var ok = 0
            var miss = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(newFile))).use { zos ->
                for (e in entries) {
                    val n = e.name
                    if (n.startsWith("fancy_icons")) continue
                    if (n.startsWith("res/") && (!keepExtras || pkgPng.matches(n))) continue
                    copyEntry(zf, e, zos)
                }
                for ((pkg, dn) in map) {
                    try {
                        val id = res.getIdentifier(dn, "drawable", pack)
                        if (id == 0) { miss++; continue }
                        var bmp = PngUtil.render(res.getDrawable(id, null))
                        if (trim) {
                            val t = PngUtil.trimAndFit(bmp, size)
                            if (t !== bmp) bmp.recycle()
                            bmp = t
                        }
                        val bytes = PngUtil.encode(bmp)
                        bmp.recycle()
                        zos.putNextEntry(ZipEntry("$iconDir/$pkg.png"))
                        zos.write(bytes)
                        zos.closeEntry()
                        ok++
                    } catch (t: Throwable) {
                        miss++
                    }
                    if ((ok + miss) % 500 == 0) log("…${ok + miss}/${map.size}")
                }
            }
            log("Icons written: $ok, skipped: $miss")
        }

        // 3. Write it back over the dummy theme
        log("Writing theme (${newFile.length() / 1024} KB)…")
        Shell.exec("am force-stop $THEME_MGR")
        val r = FileInputStream(newFile).use { Shell.exec("cat > ${Shell.q(mrcPath)}", stdin = it) }
        if (r.code != 0) error("Write failed: ${r.err.trim()}")
        val written = Shell.exec("wc -c < ${Shell.q(mrcPath)}").text.trim().toLongOrNull()
        if (written != newFile.length()) error("Size mismatch after write ($written vs ${newFile.length()}). Tap Restore backup.")
        log("Done ✓  Open Themes → Icons → apply the dummy theme's icons.")
    }

    fun restore(ctx: Context, iconsDir: String, log: (String) -> Unit) {
        val dir = ctx.getExternalFilesDir(null) ?: error("No app storage")
        val bk = dir.listFiles { f -> f.name.startsWith("backup_") }?.maxByOrNull { it.lastModified() }
            ?: error("No backup found")
        val target = "$iconsDir/${bk.name.removePrefix("backup_")}"
        log("Restoring ${bk.name}…")
        Shell.exec("am force-stop $THEME_MGR")
        val r = FileInputStream(bk).use { Shell.exec("cat > ${Shell.q(target)}", stdin = it) }
        if (r.code != 0) error("Restore failed: ${r.err.trim()}")
        log("Restored ✓")
    }

    private fun copyEntry(zf: ZipFile, e: ZipEntry, zos: ZipOutputStream) {
        val ne = ZipEntry(e.name)
        if (e.isDirectory) { zos.putNextEntry(ne); zos.closeEntry(); return }
        val data = zf.getInputStream(e).use { it.readBytes() }
        if (e.method == ZipEntry.STORED) {
            ne.method = ZipEntry.STORED
            ne.size = data.size.toLong()
            ne.compressedSize = ne.size
            ne.crc = CRC32().apply { update(data) }.value
        }
        if (e.time != -1L) ne.time = e.time
        zos.putNextEntry(ne)
        zos.write(data)
        zos.closeEntry()
    }
}
