package uk.okok.studiokanalow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import uk.okok.studiokanalow.databinding.ItemChannelBinding
import uk.okok.studiokanalow.model.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLong: ((Channel) -> Unit)? = null,
    private val epg: Boolean = false
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    companion object {
        private val cache = HashMap<String, Api.Audycja?>()
        private val wToku = HashSet<String>()
        private val limit = kotlinx.coroutines.sync.Semaphore(4)
    }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    private fun pokaz(h: VH, a: Api.Audycja?) {
        if (a == null) { h.b.tvProgram.visibility = android.view.View.GONE; h.b.pbProgram.visibility = android.view.View.GONE; return }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        h.b.tvProgram.text = fmt.format(java.util.Date(a.start * 1000)) + "  " + a.tytul
        h.b.tvProgram.visibility = android.view.View.VISIBLE
        val teraz = System.currentTimeMillis() / 1000
        if (a.koniec > a.start) {
            h.b.pbProgram.progress = (((teraz - a.start) * 100) / (a.koniec - a.start)).toInt().coerceIn(0, 100)
            h.b.pbProgram.visibility = android.view.View.VISIBLE
        } else h.b.pbProgram.visibility = android.view.View.GONE
    }

    private fun programDla(h: VH, ch: Channel) {
        val ctx = h.b.root.context
        h.b.tvProgram.visibility = android.view.View.GONE; h.b.pbProgram.visibility = android.view.View.GONE
        val id = ch.streamId
        if (!ch.directUrl.isNullOrEmpty() && !ch.directUrl!!.contains("/live/")) return
        if (cache.containsKey(id)) { pokaz(h, cache[id]); return }
        if (!wToku.add(id)) return
        scope.launch {
            val a = try {
                limit.withPermit { Api.programTv(Session.server(ctx), Session.user(ctx), Session.pass(ctx), id, 2).firstOrNull() }
            } catch (e: Exception) { null }
            cache[id] = a; wToku.remove(id)
            val pos = items.indexOfFirst { it.streamId == id }
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    private val items = ArrayList<Channel>()

    fun lista(): List<Channel> = items.toList()

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.b.tvNum.text = ch.num.toString()
        holder.b.tvName.text = ch.name
        if (!ch.logo.isNullOrEmpty()) {
            holder.b.ivLogo.load(ch.logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            holder.b.ivLogo.setImageResource(R.drawable.ic_tv)
        }
        if (epg) programDla(holder, ch) else pokaz(holder, null)
        holder.b.root.setOnClickListener { onClick(ch) }
        if (onLong != null) holder.b.root.setOnLongClickListener { onLong.invoke(ch); true }
    }

    override fun getItemCount() = items.size
}
