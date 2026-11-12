package uk.okok.okplayer

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/**
 * 2.44: realistyczne animacje startu rysowane na zywo (pasuja do kazdego ekranu, pion i poziom, bez ucinania).
 * Warianty: "odliczanie" (stara tasma 5-4-3-2-1), "strojenie" (szum TV, szukanie kanalow), "projektor" (snop swiatla w sali).
 * Kazdy konczy sie logo + paskiem ladowania z etapami. Dzwiek syntezowany (bez plikow).
 */
class StartAnimacja(c: Context, val wariant: String, val nazwa: String, val dzwiek: Boolean, val napisKoniec: String) : View(c) {
    val dlugosc = if (wariant == "projektor") 7.5f else 7f
    private var t0 = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random(System.nanoTime())
    private var bum = false; private var pik = -1
    private val kurz = List(160) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * .02f + .005f, rnd.nextFloat() * 2f + .5f) }
    private var szumBmp: Bitmap? = null
    private val gruby = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private var terkot: Thread? = null
    @Volatile private var terkocze = false

    private fun el(v: Float) = v.coerceIn(0f, 1f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); t0 = System.nanoTime()
        when (wariant) { "odliczanie", "projektor" -> startTerkot(); "strojenie" -> szum(2.6f, .22f) }
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); terkocze = false }

    // ---------- dzwiek ----------
    private fun graj(pr: ShortArray) { if (!dzwiek) return; Thread {
        try { val at = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(22050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pr.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            at.write(pr, 0, pr.size); at.play(); Thread.sleep(pr.size * 1000L / 22050 + 200); at.release() } catch (e: Exception) { } }.start() }
    private fun szum(dl: Float, gl: Float) { val n = (22050 * dl).toInt(); var lp = 0f
        graj(ShortArray(n) { i -> lp += (rnd.nextFloat() * 2 - 1 - lp) * .35f; (lp * gl * 32767 * (1 - i.toFloat() / n)).toInt().toShort() }) }
    private fun ton(f: Float, dl: Float, gl: Float) { val n = (22050 * dl).toInt()
        graj(ShortArray(n) { i -> (sin(2 * PI * f * i / 22050) * gl * 32767 * min(1f, i / 400f) * (1 - i.toFloat() / n)).toInt().toShort() }) }
    private fun huk() { val n = 22050 * 2; var faza = 0.0
        graj(ShortArray(n) { i -> val x = i.toFloat() / n; faza += 2 * PI * (110 * exp(-x * 3) + 35) / 22050
            ((sin(faza) * .8 * exp(-x * 2.5) + (rnd.nextFloat() * 2 - 1) * .15 * exp(-x * 8)) * 32767).toInt().toShort() }) }
    private fun startTerkot() { if (!dzwiek) return; terkocze = true
        terkot = Thread { while (terkocze) { val n = 700; graj(ShortArray(n) { i -> ((rnd.nextFloat() * 2 - 1) * .12f * (1 - i.toFloat() / n) * 32767).toInt().toShort() }); Thread.sleep(45) } }.apply { start() } }

    // ---------- rysowanie wspolne ----------
    private fun etap(t: Float) = when { t < 1f -> "Łączenie z serwerem…"; t < 2f -> "Ładowanie kanałów…"; t < 2.8f -> "Ładowanie filmów i seriali…"; else -> napisKoniec }

    private fun logo(cv: Canvas, a: Float, sk: Float, blask: Float, pasek: Float, napis: String) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        cv.save(); cv.translate(w / 2, h * .46f); cv.scale(sk, sk)
        val f = s * .30f; p.typeface = gruby; p.textSize = f; p.textAlign = Paint.Align.LEFT; p.alpha = 255
        val dwa = nazwa.trim().split(Regex("\\s+"), 2)
        val g1 = dwa[0].take(1); val g2 = dwa[0].drop(1)
        val w1 = p.measureText(g1); val w2 = p.measureText(g2); var x0 = -(w1 + w2) / 2
        if (w1 + w2 > w * .9f / sk) { p.textSize = f * (w * .9f / sk) / (w1 + w2); x0 = -w * .45f / sk }
        val ww1 = p.measureText(g1)
        p.setShadowLayer(s * .06f * blask + .1f, 0f, 0f, Color.argb((180 * blask).toInt(), 245, 166, 35))
        p.color = Color.argb((255 * a).toInt(), 245, 166, 35); cv.drawText(g1, x0, f * .35f, p)
        p.clearShadowLayer(); p.color = Color.argb((255 * a).toInt(), 244, 241, 236); cv.drawText(g2, x0 + ww1, f * .35f, p)
        if (dwa.size > 1) { p.textSize = s * .042f; p.textAlign = Paint.Align.CENTER; p.letterSpacing = .8f
            cv.drawText(dwa[1].uppercase(), 0f, f * .72f, p); p.letterSpacing = 0f }
        cv.restore()
        if (pasek >= 0) {
            val pw = min(w * .6f, s * .7f); val y = h * .74f; val ph = s * .012f
            p.color = Color.argb((255 * el(a * 1.5f)).toInt(), 28, 31, 37); cv.drawRoundRect(w / 2 - pw / 2, y, w / 2 + pw / 2, y + ph, ph, ph, p)
            p.color = Color.argb((255 * el(a * 1.5f)).toInt(), 245, 166, 35); p.setShadowLayer(14f, 0f, 0f, Color.rgb(245, 166, 35))
            cv.drawRoundRect(w / 2 - pw / 2, y, w / 2 - pw / 2 + pw * el(pasek), y + ph, ph, ph, p); p.clearShadowLayer()
            p.typeface = Typeface.DEFAULT; p.textSize = s * .032f; p.textAlign = Paint.Align.CENTER; p.color = Color.argb((255 * el(a * 1.5f)).toInt(), 216, 219, 226)
            cv.drawText(napis, w / 2, y + s * .07f, p)
        }
    }
    private fun ziarno(cv: Canvas, sila: Float) { val n = (width * height / 2500f * sila).toInt()
        repeat(n) { val x = rnd.nextFloat() * width; val y = rnd.nextFloat() * height; p.color = if (it % 2 == 0) Color.argb(20, 255, 255, 255) else Color.argb(30, 0, 0, 0); cv.drawRect(x, y, x + 3, y + 3, p) } }
    private fun winieta(cv: Canvas, moc: Int) { val w = width.toFloat(); val h = height.toFloat()
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * .75f, intArrayOf(0, 0, Color.argb(moc, 0, 0, 0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null }

    override fun onDraw(cv: Canvas) {
        val t = (System.nanoTime() - t0) / 1e9f
        when (wariant) { "odliczanie" -> odliczanie(cv, t); "projektor" -> projektor(cv, t); else -> strojenie(cv, t) }
        if (t > dlugosc - 1.2f) terkocze = false
        if (t < dlugosc + .5f) postInvalidateOnAnimation()
    }

    private fun odliczanie(cv: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        if (t < 4.6f) {
            val nr = 5 - (t / .9f).toInt(); val fr = (t % .9f) / .9f; val fl = .85f + rnd.nextFloat() * .15f
            cv.drawColor(Color.rgb((150 * fl).toInt(), (140 * fl).toInt(), (120 * fl).toInt()))
            val r = s * .36f; p.style = Paint.Style.STROKE; p.color = Color.rgb(34, 34, 34); p.strokeWidth = s * .012f
            cv.drawCircle(w / 2, h / 2, r, p); cv.drawCircle(w / 2, h / 2, r * .82f, p); p.strokeWidth = s * .004f
            cv.drawLine(0f, h / 2, w, h / 2, p); cv.drawLine(w / 2, 0f, w / 2, h, p); p.style = Paint.Style.FILL
            p.color = Color.argb(140, 20, 20, 20); val R = r * 1.6f; cv.drawArc(w / 2 - R, h / 2 - R, w / 2 + R, h / 2 + R, -90f, fr * 360f, true, p)
            p.color = Color.rgb(17, 17, 17); p.typeface = serif; p.textSize = s * .42f; p.textAlign = Paint.Align.CENTER
            if (nr > 0) cv.drawText("$nr", w / 2, h / 2 + s * .15f, p)
            if (nr != pik) { pik = nr; ton(1000f, .08f, .25f) }
            winieta(cv, 220); ziarno(cv, 1f)
            p.color = Color.argb(46, 255, 255, 255); p.strokeWidth = 2f
            if (rnd.nextFloat() < .5f) { val x = rnd.nextFloat() * w; cv.drawLine(x, 0f, x + rnd.nextFloat() * 8 - 4, h, p) }
        } else {
            if (!bum) { bum = true; terkocze = false; huk() }
            val u = t - 4.6f; cv.drawColor(Color.BLACK)
            p.color = Color.argb((230 * el(1 - u * 3)).toInt(), 255, 240, 210); cv.drawRect(0f, 0f, w, h, p)
            logo(cv, el(u * 2), .85f + .15f * el(u * 1.5f), el(u), el((u - .3f) / 1.8f), etap(u * 1.6f)); ziarno(cv, .4f)
        }
    }

    private fun strojenie(cv: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        val sw = max(1, width / 4); val sh = max(1, height / 4)
        val b = szumBmp ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { szumBmp = it }
        val sz = el(1 - (t - 2.2f) / .8f); val px = IntArray(sw * sh) { val v = (rnd.nextInt(256) * sz).toInt(); Color.rgb(v, v, v) }
        b.setPixels(px, 0, sw, 0, 0, sw, sh); cv.drawBitmap(b, null, RectF(0f, 0f, w, h), null)
        if (t < 2.8f) {
            val k = min(9999, ((t / 2.6f).pow(2) * 9999).toInt())
            p.color = Color.rgb(0, 255, 0); p.typeface = Typeface.MONOSPACE; p.textAlign = Paint.Align.LEFT; p.textSize = s * .05f
            p.setShadowLayer(8f, 0f, 0f, Color.GREEN)
            cv.drawText("CH " + k.toString().padStart(4, '0'), w * .06f, h * .06f + s * .05f, p)
            p.textSize = s * .03f; cv.drawText("SZUKANIE KANAŁÓW…", w * .06f, h * .06f + s * .11f, p); p.clearShadowLayer()
            p.color = Color.argb(150, 0, 0, 0); cv.drawRect(w * .2f, h * .85f, w * .8f, h * .85f + s * .02f, p)
            p.color = Color.GREEN; cv.drawRect(w * .2f, h * .85f, w * .2f + w * .6f * el(t / 2.6f), h * .85f + s * .02f, p)
            if (rnd.nextFloat() < .3f) { p.color = Color.argb(38, 255, 255, 255); val y = rnd.nextFloat() * h; cv.drawRect(0f, y, w, y + s * .02f, p) }
        }
        if (t > 2.2f) {
            if (!bum) { bum = true; ton(880f, .25f, .2f); postDelayed({ huk() }, 250) }
            val u = t - 2.2f; val sx = 1 + .3f * el(1 - u * 4)
            p.color = Color.argb((230 * el(u * 2)).toInt(), 0, 0, 0); cv.drawRect(0f, 0f, w, h, p)
            cv.save(); cv.scale(1f, 1 / sx, w / 2, h / 2)
            p.shader = RadialGradient(w / 2, h * .46f, s * .6f, Color.argb(150, 60, 40, 10), 0, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w, h, p); p.shader = null
            logo(cv, el(u * 2.5f), 1f, el(u), el((u - .4f) / 2.4f), etap(1 + u * .7f)); cv.restore()
            p.color = Color.argb(46, 0, 0, 0); var y = 0f; while (y < h) { cv.drawRect(0f, y, w, y + 1.5f, p); y += 4f }
        }
    }

    private fun projektor(cv: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h)
        val zap = el((t - .4f) / .6f); cv.drawColor(Color.rgb(5, 3, 2))
        val px = w / 2; val py = h * 1.02f; val ex = w * .08f; val ey = h * .12f; val ew = w * .84f; val eh = h * .6f
        p.color = Color.rgb((30 + 90 * zap).toInt(), (30 + 80 * zap).toInt(), (28 + 60 * zap).toInt()); cv.drawRect(ex, ey, ex + ew, ey + eh, p)
        if (zap > 0) {
            p.shader = LinearGradient(0f, py, 0f, ey, Color.argb((90 * zap).toInt(), 255, 235, 200), Color.argb((15 * zap).toInt(), 255, 235, 200), Shader.TileMode.CLAMP)
            val sn = Path().apply { moveTo(px - s * .01f, py); lineTo(ex, ey); lineTo(ex + ew, ey); lineTo(px + s * .01f, py); close() }
            cv.drawPath(sn, p); p.shader = null
            kurz.forEach { k -> k[1] -= k[2] * .1f; k[0] += sin(t * 2 + k[1] * 9) * .0008f; if (k[1] < 0) k[1] = 1f
                val yy = ey + (py - ey) * k[1]; val sz = (yy - ey) / (py - ey); val xx = px + (ex + ew * k[0] - px) * (1 - sz)
                p.color = Color.argb((128 * zap).toInt(), 255, 240, 210); cv.drawCircle(xx, yy, k[3] * 2, p) }
        }
        val u = t - 1.6f
        cv.save(); cv.clipRect(ex, ey, ex + ew, ey + eh)
        if (u > 0) { if (!bum) { bum = true; huk() }; cv.translate(0f, -h * .02f); logo(cv, el(u * 1.4f) * (.9f + rnd.nextFloat() * .1f), .8f, el(u) * .7f, -1f, "") }
        cv.restore(); ziarno(cv, .3f)
        p.color = Color.BLACK; for (i in 0 until 9) cv.drawOval(w * (i + .5f) / 9 - w * .06f, h * .98f - h * .1f, w * (i + .5f) / 9 + w * .06f, h * .98f + h * .1f, p)
        winieta(cv, 180)
        if (u > 0) { val pw = min(w * .6f, s * .7f); val y = ey + eh + s * .06f; val ph = s * .012f
            p.color = Color.rgb(28, 31, 37); cv.drawRoundRect(w / 2 - pw / 2, y, w / 2 + pw / 2, y + ph, ph, ph, p)
            p.color = Color.rgb(245, 166, 35); cv.drawRoundRect(w / 2 - pw / 2, y, w / 2 - pw / 2 + pw * el((u - .3f) / 3f), y + ph, ph, ph, p)
            p.typeface = Typeface.DEFAULT; p.textSize = s * .032f; p.textAlign = Paint.Align.CENTER; p.color = Color.rgb(216, 219, 226); cv.drawText(etap(u * .9f), w / 2, y + s * .07f, p) }
    }
}
