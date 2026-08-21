package com.blelink.app

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Bounded gallery of received photos. Decoded bitmaps are far larger in
 * memory than their compressed wire size, so the list is capped and the
 * oldest photo is evicted once the cap is exceeded to avoid OOM over a
 * long session. The most recent photo (position 0) renders as a larger
 * "hero" tile spanning the full grid width; see MainActivity's
 * GridLayoutManager.SpanSizeLookup for the span side of this.
 */
class PhotoAdapter(private val maxPhotos: Int = 30) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    companion object {
        const val VIEW_TYPE_HERO = 0
        const val VIEW_TYPE_THUMB = 1
    }

    private val photos = mutableListOf<Bitmap>()

    fun addPhoto(bitmap: Bitmap) {
        val hadHero = photos.isNotEmpty()
        photos.add(0, bitmap)
        notifyItemInserted(0)
        if (hadHero) {
            // The old hero (position 0) is now a regular thumb at position 1 — its view
            // type changed, so it needs an explicit rebind to swap to the thumb layout.
            notifyItemChanged(1)
        }
        while (photos.size > maxPhotos) {
            val lastIndex = photos.size - 1
            photos.removeAt(lastIndex)
            notifyItemRemoved(lastIndex)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_HERO else VIEW_TYPE_THUMB
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_HERO) R.layout.item_photo_hero else R.layout.item_photo
        val card = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false) as MaterialCardView
        val imageView = card.getChildAt(0) as ImageView
        return PhotoViewHolder(card, imageView)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.imageView.setImageBitmap(photos[position])
    }

    override fun getItemCount(): Int = photos.size

    class PhotoViewHolder(card: MaterialCardView, val imageView: ImageView) : RecyclerView.ViewHolder(card)
}
