package com.xmb.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import java.text.Collator

enum class Kind { GAME, MUSIC, VIDEO, PHOTO, NETWORK }

class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: Bitmap,
    val kinds: Set<Kind>,
)

/** Собирает список установленных приложений и раскладывает их по категориям XMB. */
object AppRepository {

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val iconPx = (ctx.resources.displayMetrics.density * 72f).toInt().coerceIn(96, 192)

        val launchables = query(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        val browsers = packages(pm, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE))
        val cameras = packages(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val audioPlayers = packages(pm, Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/audio/media/1"), "audio/*"))
        val videoPlayers = packages(pm, Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/*"))
        val galleries = packages(pm, Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/images/media/1"), "image/*"))

        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        for (ri in launchables) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg == ctx.packageName) continue
            val key = pkg + "/" + ai.name
            if (!seen.add(key)) continue

            val app = ai.applicationInfo
            val kinds = HashSet<Kind>()
            @Suppress("DEPRECATION")
            val gameFlag = (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val undefined = app.category == ApplicationInfo.CATEGORY_UNDEFINED

            if (app.category == ApplicationInfo.CATEGORY_GAME || gameFlag) kinds += Kind.GAME
            if (app.category == ApplicationInfo.CATEGORY_AUDIO || (undefined && pkg in audioPlayers && pkg !in browsers)) kinds += Kind.MUSIC
            if (app.category == ApplicationInfo.CATEGORY_VIDEO || (undefined && pkg in videoPlayers && pkg !in browsers)) kinds += Kind.VIDEO
            if (app.category == ApplicationInfo.CATEGORY_IMAGE || pkg in cameras || (undefined && pkg in galleries && pkg !in browsers)) kinds += Kind.PHOTO
            if (pkg in browsers ||
                app.category == ApplicationInfo.CATEGORY_SOCIAL ||
                app.category == ApplicationInfo.CATEGORY_NEWS ||
                app.category == ApplicationInfo.CATEGORY_MAPS
            ) kinds += Kind.NETWORK

            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val icon = try { ri.loadIcon(pm).toBitmap(iconPx) } catch (_: Exception) { null } ?: continue

            result += AppEntry(label, ComponentName(pkg, ai.name), icon, kinds)
        }

        val collator = Collator.getInstance()
        return result.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }

    private fun packages(pm: PackageManager, intent: Intent): Set<String> =
        query(pm, intent).mapNotNull { it.activityInfo?.packageName }.toSet()

    private fun Drawable.toBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bmp
    }
}
