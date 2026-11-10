package uk.okok.studiokanalow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import uk.okok.studiokanalow.databinding.ItemTileBinding
import uk.okok.studiokanalow.model.Channel

/** Kafle z okladkami - do Filmow/Seriali. */
class TileAdapter(
    private val widthDp: Int = 0,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<TileAdapter.VH>() {

    private val items = ArrayList<Channel>()

    fun submit(list: List<Channel>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(val b: ItemTileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        if (widthDp > 0) {
            val dens = holder.b.root.resources.displayMetrics.density
            holder.b.root.layoutParams = RecyclerView.LayoutParams((widthDp * dens).toInt(), RecyclerView.LayoutParams.WRAP_CONTENT)
            val lp = holder.b.ivPoster.layoutParams
            lp.height = (widthDp * 1.5f * dens).toInt()
            holder.b.ivPoster.layoutParams = lp
        }
        holder.b.tvTitle.text = ch.name
        if (!ch.logo.isNullOrEmpty()) {
            holder.b.ivPoster.load(ch.logo) {
                placeholder(R.drawable.ic_movie); error(R.drawable.ic_movie)
            }
        } else {
            holder.b.ivPoster.setImageResource(R.drawable.ic_movie)
        }
        holder.b.root.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
