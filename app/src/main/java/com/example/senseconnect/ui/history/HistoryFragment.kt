package com.example.senseconnect.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.senseconnect.R
import com.example.senseconnect.SenseConnectApp
import com.example.senseconnect.core.activitylog.ActivityEvent
import com.example.senseconnect.core.activitylog.ActivityType
import com.example.senseconnect.core.ui.Feedback
import com.example.senseconnect.core.ui.TimeFormat
import com.example.senseconnect.core.ui.bind
import com.example.senseconnect.core.ui.tintTile
import com.example.senseconnect.core.ui.visual
import com.example.senseconnect.databinding.FragmentHistoryBinding
import com.example.senseconnect.databinding.ItemActivityRowBinding
import com.example.senseconnect.databinding.ItemHistoryHeaderBinding
import com.example.senseconnect.databinding.ItemSummaryStatBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Activity history: what SenseConnect helped with, grouped by day and filterable by module. */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val repository get() = (requireActivity().application as SenseConnectApp).container.activityLog
    private val adapter = HistoryAdapter()
    private var filter: ActivityType? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        setupFilters()

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    repository.clear()
                    Feedback.confirm(binding.root)
                    Snackbar.make(binding.root, R.string.history_cleared, Snackbar.LENGTH_SHORT).show()
                }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.events.collect(::render)
            }
        }
    }

    private fun setupFilters() {
        val options = listOf<Pair<ActivityType?, String>>(
            null to getString(R.string.filter_all),
            ActivityType.VISION to getString(R.string.module_vision),
            ActivityType.HEARING to getString(R.string.module_hearing),
            ActivityType.COMMUNICATION to getString(R.string.module_communication),
            ActivityType.SOS to getString(R.string.module_sos),
            ActivityType.LOCATION to getString(R.string.module_location),
        )
        options.forEach { (type, label) ->
            val chip = Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                text = label
                isCheckable = true
                isChecked = type == filter
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        filter = type
                        render(repository.events.value)
                    }
                }
            }
            binding.filterChips.addView(chip)
        }
    }

    private fun render(all: List<ActivityEvent>) {
        renderSummary(all)
        val events = filter?.let { f -> all.filter { it.type == f } } ?: all
        binding.btnClear.isEnabled = all.isNotEmpty()
        binding.emptyState.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyTitle.setText(if (all.isEmpty()) R.string.history_empty_title else R.string.history_empty_filtered)

        val rows = mutableListOf<HistoryRow>()
        var lastLabel: String? = null
        events.forEach { e ->
            val label = TimeFormat.dayLabel(e.timestamp)
            if (label != lastLabel) {
                rows += HistoryRow.Header(label)
                lastLabel = label
            }
            rows += HistoryRow.Item(e)
        }
        if (events.isNotEmpty()) rows += HistoryRow.Footer
        adapter.submitList(rows)
    }

    private fun renderSummary(all: List<ActivityEvent>) {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val recent = all.filter { it.timestamp >= weekAgo }
        binding.summaryRow.removeAllViews()
        listOf(ActivityType.VISION, ActivityType.HEARING, ActivityType.COMMUNICATION, ActivityType.SOS).forEach { type ->
            val stat = ItemSummaryStatBinding.inflate(layoutInflater, binding.summaryRow, true)
            val v = type.visual()
            val count = recent.count { it.type == type }
            stat.icon.setImageResource(v.icon)
            stat.icon.tintTile(v.container, v.onContainer)
            stat.count.text = count.toString()
            stat.label.text = v.label
            stat.root.contentDescription = getString(R.string.cd_week_stat, count, v.label)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private sealed interface HistoryRow {
    data class Header(val label: String) : HistoryRow
    data class Item(val event: ActivityEvent) : HistoryRow
    data object Footer : HistoryRow
}

private class HistoryAdapter : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryRow.Header -> 0
        is HistoryRow.Item -> 1
        HistoryRow.Footer -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
            1 -> EventHolder(ItemActivityRowBinding.inflate(inflater, parent, false))
            else -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_history_footer, parent, false)) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Header -> (holder as HeaderHolder).binding.label.text = row.label
            is HistoryRow.Item -> (holder as EventHolder).binding.bind(row.event)
            HistoryRow.Footer -> Unit
        }
    }

    class HeaderHolder(val binding: ItemHistoryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class EventHolder(val binding: ItemActivityRowBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(a: HistoryRow, b: HistoryRow) = when {
            a is HistoryRow.Item && b is HistoryRow.Item -> a.event.id == b.event.id
            else -> a == b
        }
        override fun areContentsTheSame(a: HistoryRow, b: HistoryRow) = a == b
    }
}
