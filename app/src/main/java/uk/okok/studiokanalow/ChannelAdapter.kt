package uk.okok.studiokanalow

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import uk.okok.studiokanalow.databinding.ItemChannelBinding
import uk.okok.studiokanalow.model.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()

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
        holder.b.root.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
