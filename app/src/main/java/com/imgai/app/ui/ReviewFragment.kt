package com.imgai.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.imgai.app.R
import com.imgai.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewFragment : Fragment() {

    private lateinit var tvEmpty: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_review, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvEmpty = view.findViewById(R.id.tvReviewEmpty)
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val pending = withContext(Dispatchers.IO) { db.photoDao().getPendingReview() }
            val dupGroups = withContext(Dispatchers.IO) { db.duplicateGroupDao().getAll() }

            tvEmpty.visibility = if (pending.isEmpty() && dupGroups.isEmpty()) View.VISIBLE else View.GONE
            tvEmpty.text = if (pending.isEmpty() && dupGroups.isEmpty()) {
                "暂无待确认照片\n\n聚类完成后，重复/低质量照片会出现在这里"
            } else {
                "待确认: ${pending.size} 张照片\n重复组: ${dupGroups.size} 组"
            }
        }
    }
}
