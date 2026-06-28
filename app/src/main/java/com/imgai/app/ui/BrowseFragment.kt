package com.imgai.app.ui

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
        adapter = CategoryAdapter()
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
            val photoCount = withContext(Dispatchers.IO) { db.photoDao().count() }

            val items = mutableListOf<CategoryItem>()

            // 大类
            for (cat in categories) {
                val count = withContext(Dispatchers.IO) {
                    db.photoDao().getByCategory(cat.id).size
                }
                items.add(CategoryItem(cat.name, count, isHeader = true))
            }

            // 人物子类
            if (clusters.isNotEmpty()) {
                items.add(CategoryItem("── 人物明细 ──", 0, isHeader = false, isDivider = true))
                for (cluster in clusters) {
                    items.add(CategoryItem(cluster.label, cluster.memberCount, isHeader = false))
                }
            }

            adapter.submitList(items)
        }
    }

    data class CategoryItem(
        val name: String,
        val count: Int,
        val isHeader: Boolean = false,
        val isDivider: Boolean = false
    )

    class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private val items = mutableListOf<CategoryItem>()

        fun submitList(newItems: List<CategoryItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 32, 48, 32)
                textSize = 16f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            (holder.itemView as TextView).apply {
                text = if (item.isHeader) {
                    "📁 ${item.name}    (${item.count})"
                } else if (item.isDivider) {
                    item.name
                } else {
                    "   👤 ${item.name}    (${item.count})"
                }
                alpha = if (item.isDivider) 0.4f else 1f
            }
        }

        override fun getItemCount() = items.size
        class VH(view: android.view.View) : RecyclerView.ViewHolder(view)
    }
}
