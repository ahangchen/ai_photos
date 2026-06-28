package com.imgai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.imgai.app.R
import com.imgai.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 照片网格页 — 展示某分类/人物下的所有照片
 *
 * Intent extras:
 *   title: String     — 标题
 *   type: String      — "cluster" 或 "category"
 *   id: Long          — clusterId 或 categoryId
 */
class PhotoGridActivity : AppCompatActivity() {

    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var adapter: PhotoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_grid)

        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)
        rvPhotos = findViewById(R.id.rvPhotos)

        val title = intent.getStringExtra("title") ?: "照片"
        val type = intent.getStringExtra("type") ?: "cluster"
        val id = intent.getLongExtra("id", -1)

        tvTitle.text = title
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        rvPhotos.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter()
        rvPhotos.adapter = adapter

        loadPhotos(type, id)
    }

    private fun loadPhotos(type: String, id: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@PhotoGridActivity)
            val photos = withContext(Dispatchers.IO) {
                when (type) {
                    "cluster" -> {
                        // 通过 face_embeddings 找到该人物的所有照片 URI
                        val embeddings = db.faceEmbeddingDao().getByCluster(id)
                        val uris = embeddings.map { it.imageUri }.distinct()
                        uris
                    }
                    "category" -> {
                        db.photoDao().getByCategory(id).map { it.uri }
                    }
                    else -> emptyList()
                }
            }

            tvCount.text = "${photos.size} 张"
            adapter.submitList(photos)
        }
    }

    // ── Adapter ──
    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.VH>() {
        private val items = mutableListOf<String>()

        fun submitList(uris: List<String>) {
            items.clear()
            items.addAll(uris)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = items[position]
            Glide.with(holder.itemView)
                .load(Uri.parse(uri))
                .centerCrop()
                .into(holder.imgPhoto)

            holder.itemView.setOnClickListener {
                // TODO: 打开大图查看
            }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imgPhoto: ImageView = view.findViewById(R.id.imgPhoto)
        }
    }
}
