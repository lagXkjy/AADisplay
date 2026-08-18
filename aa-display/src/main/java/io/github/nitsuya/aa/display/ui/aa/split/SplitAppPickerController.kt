package io.github.nitsuya.aa.display.ui.aa.split

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.Log
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.FragmentAaMainBinding
import io.github.nitsuya.aa.display.databinding.ItemSplitAppBinding
import io.github.nitsuya.aa.display.util.AABroadcastConst
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class SplitAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * Bottom overlay app picker embedded in the split shell.
 * Lists recent VD/phone tasks first, then all launchable apps.
 */
class SplitAppPickerController(
    private val binding: FragmentAaMainBinding,
) {
    companion object {
        private const val TAG = "AADisplay_AppPicker"
        private fun logPicker(msg: String) = Log.d(TAG, msg)
    }
    private var targetPane: Int = SplitPane.PRIMARY
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private val adapter = Adapter { entry ->
        logPicker("pick pane=$targetPane pkg=${entry.packageName} label=${entry.label}")
        CoreApi.startActivityOnPane(entry.packageName, 0, targetPane)
        hide()
        onAppPicked?.invoke(targetPane, entry.packageName)
    }

    var onAppPicked: ((pane: Int, packageName: String) -> Unit)? = null
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    init {
        val density = binding.root.resources.displayMetrics.density
        val widthDp = binding.root.resources.displayMetrics.widthPixels / density
        val spanCount = (widthDp / 80f).toInt().coerceIn(4, 8)
        val glm = GridLayoutManager(binding.root.context, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == Adapter.TYPE_HEADER) spanCount else 1
            }
        }
        binding.rvAppPicker.layoutManager = glm
        binding.rvAppPicker.adapter = adapter
        binding.btnPickerClose.setOnClickListener { hide() }
        // Scrim dismiss only. Do NOT mark the sheet LinearLayout clickable — on Samsung
        // OneUI a clickable parent of RecyclerView often swallows item taps (seen as
        // "first app in the list does nothing" / Alook in 最近).
        binding.appPickerHost.setOnClickListener { hide() }
        val sheet = binding.appPickerHost.getChildAt(0) as? ViewGroup
        sheet?.isClickable = false
        sheet?.isFocusable = false
    }

    fun show(pane: Int) {
        targetPane = pane
        // Above split divider peel (elevation 8) so left-column icons stay tappable.
        binding.appPickerHost.elevation = 32f
        binding.appPickerHost.bringToFront()
        binding.appPickerHost.isVisible = true
        binding.splitDivider.isEnabled = false
        setAaUiRailConsume(true)
        onVisibilityChanged?.invoke(true)
        val gen = loadGeneration.incrementAndGet()
        loadExecutor.execute {
            val rows = try {
                buildPickerRows()
            } catch (_: Throwable) {
                emptyList()
            }
            mainHandler.post {
                if (gen != loadGeneration.get() || !binding.appPickerHost.isVisible) return@post
                adapter.submit(rows)
            }
        }
    }

    fun hide() {
        loadGeneration.incrementAndGet()
        binding.appPickerHost.isVisible = false
        binding.appPickerHost.elevation = 0f
        binding.splitDivider.isEnabled = true
        setAaUiRailConsume(false)
        onVisibilityChanged?.invoke(false)
    }

    /** Tell Coolwalk rail-steal to inject into AaDisplay UI while the picker is open. */
    private fun setAaUiRailConsume(consume: Boolean) {
        try {
            binding.root.context.sendBroadcast(
                Intent(AABroadcastConst.ACTION_AA_UI_RAIL_CONSUME).putExtra(
                    AABroadcastConst.EXTRA_AA_UI_RAIL_CONSUME,
                    consume,
                )
            )
        } catch (_: Throwable) {
        }
    }

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class App(val entry: SplitAppEntry) : Row()
    }

    private fun buildPickerRows(): List<Row> {
        val all = loadLaunchableApps()
        val byPkg = all.associateBy { it.packageName }
        val recentPkgs = loadRecentPackages()
        val recentEntries = recentPkgs.mapNotNull { byPkg[it] }.distinctBy { it.packageName }
        val recentSet = recentEntries.map { it.packageName }.toSet()
        val rest = all.filter { it.packageName !in recentSet }
        val rows = mutableListOf<Row>()
        if (recentEntries.isNotEmpty()) {
            rows += Row.Header("最近")
            recentEntries.forEach { rows += Row.App(it) }
        }
        rows += Row.Header("全部应用")
        rest.forEach { rows += Row.App(it) }
        return rows
    }

    private fun loadRecentPackages(): List<String> {
        val recent = try {
            CoreApi.recentTask
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val ordered = linkedSetOf<String>()
        recent.virtualDisplay.forEach { info ->
            info.packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered.add(it) }
        }
        recent.mainDisplay.forEach { info ->
            info.packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered.add(it) }
        }
        return ordered.toList()
    }

    private fun loadLaunchableApps(): List<SplitAppEntry> {
        val pm = binding.root.context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        )
        return resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName
            if (pkg == BuildConfig.APPLICATION_ID) return@mapNotNull null
            if (pkg == "android" || pkg == "com.android.systemui") return@mapNotNull null
            SplitAppEntry(
                packageName = pkg,
                label = ai.loadLabel(pm)?.toString() ?: pkg,
                icon = ai.loadIcon(pm),
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private class Adapter(
        private val onClick: (SplitAppEntry) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_APP = 1
        }

        private val items = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.App -> TYPE_APP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val tv = inflater.inflate(R.layout.item_split_section, parent, false)
                HeaderVH(tv as android.widget.TextView)
            } else {
                VH(ItemSplitAppBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder as HeaderVH).tv.text = row.title
                is Row.App -> {
                    val b = (holder as VH).b
                    b.tvLabel.text = row.entry.label
                    b.ivIcon.setImageDrawable(row.entry.icon)
                    b.root.isClickable = true
                    b.root.isFocusable = true
                    b.root.setOnClickListener { onClick(row.entry) }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(val b: ItemSplitAppBinding) : RecyclerView.ViewHolder(b.root)
        class HeaderVH(val tv: android.widget.TextView) : RecyclerView.ViewHolder(tv)
    }
}
