package uk.okok.okplayer

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

/**
 * Jezyk interfejsu wedlug ustawien systemu urzadzenia: polski, angielski, niemiecki, ukrainski, rosyjski,
 * hiszpanski, francuski, wloski, czeski, litewski, rumunski; kazdy inny jezyk -> angielski.
 * Tlumaczenia w assets/jezyki.tsv. Tytuly filmow i program TV zostaja jak na serwerze.
 */
object Jezyk {
    private var calosc: Map<String, String> = emptyMap()
    private var fragmenty: List<Pair<String, String>> = emptyList()
    var kod = "pl"; private set
    val loc: Locale get() = Locale.getDefault()

    fun init(c: Context) {
        if (calosc.isNotEmpty() || fragmenty.isNotEmpty()) return
        val sys = Locale.getDefault().language
        try {
            val linie = c.assets.open("jezyki.tsv").bufferedReader(Charsets.UTF_8).readLines()
            val kol = linie.first().split("\t")
            if (sys == "pl") { kod = "pl"; return }
            val i = kol.indexOf(sys).takeIf { it > 0 } ?: kol.indexOf("en")
            kod = kol[i]
            val cal = HashMap<String, String>(); val fr = ArrayList<Pair<String, String>>()
            for (l in linie.drop(1)) {
                val p = l.split("\t"); if (p.size <= i) continue
                val pl = p[0]; val tr = p[i]
                if (pl.startsWith("=")) cal[pl.substring(1)] = tr else fr.add(pl to tr)
            }
            calosc = cal; fragmenty = fr.sortedByDescending { it.first.length }
        } catch (_: Exception) { }
    }

    /** Tlumaczy napis (caly albo jego stale fragmenty, np. "Teraz: ..."). */
    fun t(s: String): String {
        if (kod == "pl" || s.isEmpty()) return s
        calosc[s.trim()]?.let { return s.replace(s.trim(), it) }
        var w = s
        for ((pl, tr) in fragmenty) if (w.contains(pl)) w = w.replace(pl, tr)
        return w
    }

    /** Tlumaczy wszystkie napisy w widoku (i nowe, gdy sie pojawia). */
    fun wlacz(a: Activity) {
        init(a)
        if (kod == "pl") return
        a.window.decorView.viewTreeObserver.addOnGlobalLayoutListener { przetlumacz(a.window.decorView) }
    }

    fun przetlumacz(v: View) {
        if (kod == "pl") return
        if (v is TextView) {
            val s = v.text?.toString() ?: ""
            if (s.isNotEmpty() && v.getTag(R.id.jezyk_tekst) != s) { val t = t(s); if (t != s) v.text = t; v.setTag(R.id.jezyk_tekst, t) }
            val h = v.hint?.toString() ?: ""
            if (h.isNotEmpty() && v.getTag(R.id.jezyk_podpowiedz) != h) { val t = t(h); if (t != h) v.hint = t; v.setTag(R.id.jezyk_podpowiedz, t) }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) przetlumacz(v.getChildAt(i))
    }

    /** Okna (dialogi): tlumaczenie po pokazaniu. */
    fun okno(d: android.app.Dialog) { if (kod == "pl") return; d.window?.decorView?.let { dv -> przetlumacz(dv); dv.viewTreeObserver.addOnGlobalLayoutListener { przetlumacz(dv) } } }
}
