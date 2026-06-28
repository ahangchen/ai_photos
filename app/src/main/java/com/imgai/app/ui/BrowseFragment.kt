package com.imgai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.imgai.app.R
import com.imgai.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseFragment : Fragment() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var adapter: CategoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_browse, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvCategories = view.findViewById(R.id.rvCategories)
        rvCategories.layoutManager = LinearLayoutManager(requireContext())
        adapter = CategoryAdapter { item -> openPhotoGrid(item) }
        rvCategories.adapter = adapter
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val categories = withContext(Dispatchers.IO) { db.categoryDao().getAll() }
            val clusters = withContext(Dispatchers.IO) { db.faceClusterDao().getAll() }

            val items = mutableListOf<CategoryItem>()

            // 大类
            for (cat in categories) {
                val count = withContext(Dispatchers.IO) {
                    db.photoDao().getByCategory(cat.id).size
                }
                items.add(CategoryItem(
                    title = cat.name,
                    subtitle = "${count} 张",
                    type = "category",
                    id = cat.id,
                    isClickable = count > 0
                ))
            }

            // 人物子类
            if (clusters.isNotEmpty()) {
                items.add(CategoryItem(
                    title = "── 人物明细 ──",
                    subtitle = "",
                    type = "divider",
                    id = -1,
                    isClickable = false
                ))
                for (cluster in clusters) {
                    items.add(CategoryItem(
                        title = cluster.label,
                        subtitle = "${cluster.memberCount} 张",
                        type = "cluster",
                        id = cluster.id,
                        isClickable = true
                    ))
                }
            }

            adapter.submitList(items)
        }
    }

    private fun openPhotoGrid(item: CategoryItem) {
        if (!item.isClickable) return
        val intent = Intent(requireContext(), PhotoGridActivity::class.java).apply {
            putExtra("title", item.title)
            putExtra("type", item.type)
            putExtra("id", item.id)
        }
        startActivity(intent)
    }

    data class CategoryItem(
        val title: String,
        val subtitle: String,
        val type: String,       // category / cluster / divider
        val id: Long,
        val isClickable: Boolean
    )

    class CategoryAdapter(
        private val onClick: (CategoryItem) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private val items = mutableListOf<CategoryItem>()

        fun submitList(newItems: List<CategoryItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 36, 48, 36)
                textSize = 16f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            (holder.itemView as TextView).apply {
                text = when {
                    item.type == "divider" -> item.title
                    item.type == "cluster" -> "   👤 ${item.title}    ${item.subtitle}"
                    else -> "📁 ${item.title}    ${item.subtitle}"
                }
                alpha = if (item.type == "divider") 0.4f else 1f
                isClickable = item.isClickable

                setOnClickListener {
                    if (item.isClickable) {
                        onClick(item)
                    }
                }
            }
        }

        override fun getItemCount() = items.size
        class VH(view: View) : RecyclerView.ViewHolder(view)
    }
}
