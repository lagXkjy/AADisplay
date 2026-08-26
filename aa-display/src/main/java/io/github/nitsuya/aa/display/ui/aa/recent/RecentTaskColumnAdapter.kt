package io.github.nitsuya.aa.display.ui.aa.recent

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.RecentTaskBinding
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.PaneAppStack
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * Recent-task column for phone / primary VD / secondary VD.
 *
 * Layout: `[Primary(VD1) | Secondary(VD2) | Phone]`.
 * Mutations go through [RecentTasksCoordinator]; this adapter only renders rows.
 */
class RecentTaskColumnAdapter(
    private val recyclerView: RecyclerView,
    /** null = phone stack; [SplitPane.PRIMARY] / [SplitPane.SECONDARY] = VD stacks. */
    private val stackPane: Int? = null,
    private val host: Host,
) : RecyclerView.Adapter<RecentTaskColumnAdapter.ViewHolder>() {

    interface Host {
        fun onOpenTask(stackPane: Int?, item: RecentTaskInfo)
        fun onCloseTask(item: RecentTaskInfo)
        fun onPhoneSwipeLeft(item: RecentTaskInfo)
        fun onPhoneSwipeRight(item: RecentTaskInfo)
        fun onPrimarySwipeLeft(item: RecentTaskInfo)
        fun onPrimarySwipeRight(item: RecentTaskInfo)
        fun onSecondarySwipeLeft(item: RecentTaskInfo)
        fun onSecondarySwipeRight(item: RecentTaskInfo)
        fun onReorderPaneStack(pane: Int, packagesTopToBottom: List<String>)
        fun onDismissOverlay()
        fun focusedPane(): Int
    }

    private val items: MutableList<RecentTaskInfo> = ArrayList()
    private var stackLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val itemTouchHelper = ItemTouchHelper(ItemTouchHelperCallback())

    init {
        itemTouchHelper.attachToRecyclerView(recyclerView)
        if (stackPane != null) {
            recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
            ensureStackItemHeights()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            RecentTaskBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.ivIcon.setImageBitmap(item.logo)
        holder.binding.tvName.text = item.label

        if (stackPane != null) {
            applyCompactStackItemLayout(holder)
        }
        if (stackPane == null) {
            holder.binding.root.setOnClickListener { host.onDismissOverlay() }
            arrayOf(holder.binding.tvName, holder.binding.ivIcon).forEach {
                it.setOnClickListener { host.onOpenTask(null, item) }
            }
            holder.binding.vCard.setOnClickListener(null)
            holder.binding.vCard.isClickable = false
        } else {
            val bringToTop = View.OnClickListener { host.onOpenTask(stackPane, item) }
            holder.binding.root.setOnClickListener(bringToTop)
            holder.binding.tvName.setOnClickListener(bringToTop)
            holder.binding.ivIcon.setOnClickListener(bringToTop)
            holder.binding.vCard.setOnClickListener(bringToTop)
        }
        holder.binding.ibClose.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        holder.binding.ibClose.setOnClickListener {
            host.onCloseTask(item)
        }
    }

    private fun applyCompactStackItemLayout(holder: ViewHolder) {
        val slotH = stackSlotHeight()
        if (slotH > 0) {
            val lp = holder.itemView.layoutParams
                ?: RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    slotH,
                )
            if (lp.height != slotH) {
                lp.height = slotH
                holder.itemView.layoutParams = lp
            }
        }
        if (holder.compactLayoutApplied) return
        holder.compactLayoutApplied = true
        val density = holder.itemView.resources.displayMetrics.density
        val gapPx = (2f * density).toInt()
        (holder.binding.clItem.layoutParams as? ViewGroup.MarginLayoutParams)?.let { mlp ->
            if (mlp.topMargin != 0 || mlp.bottomMargin != 0 ||
                mlp.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                mlp.topMargin = 0
                mlp.bottomMargin = 0
                mlp.height = ViewGroup.LayoutParams.MATCH_PARENT
                holder.binding.clItem.layoutParams = mlp
            }
        }
        holder.binding.clItem.setPadding(0, gapPx, 0, gapPx)
        ConstraintSet().apply {
            clone(holder.binding.clItem)
            clear(R.id.v_card, ConstraintSet.TOP)
            connect(
                R.id.v_card,
                ConstraintSet.TOP,
                R.id.iv_icon,
                ConstraintSet.BOTTOM,
                gapPx,
            )
            connect(R.id.v_card, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(R.id.v_card, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.v_card, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainPercentWidth(R.id.v_card, 0.72f)
            setDimensionRatio(R.id.v_card, "")
            constrainHeight(R.id.v_card, ConstraintSet.MATCH_CONSTRAINT)
            applyTo(holder.binding.clItem)
        }
        val closeSize = (36f * density).toInt()
        holder.binding.ibClose.layoutParams = holder.binding.ibClose.layoutParams.apply {
            width = closeSize
            height = closeSize
        }
    }

    private fun stackSlotHeight(): Int {
        val h = recyclerView.height
        if (h <= 0) return 0
        return h / PaneAppStack.MAX_PER_PANE
    }

    private fun ensureStackItemHeights() {
        if (stackLayoutListener != null) return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (recyclerView.height <= 0) return
                recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                stackLayoutListener = null
                if (items.isNotEmpty()) {
                    notifyDataSetChanged()
                }
            }
        }
        stackLayoutListener = listener
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<RecentTaskInfo>) {
        val capped = if (newItems.isNotEmpty()) {
            if (stackPane != null) {
                newItems.take(PaneAppStack.MAX_PER_PANE)
            } else {
                newItems
            }
        } else {
            emptyList()
        }
        if (items.size == capped.size &&
            items.indices.all { items[it].taskId == capped[it].taskId }
        ) {
            return
        }
        val old = ArrayList(items)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = capped.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].taskId == capped[newPos].taskId
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].taskId == capped[newPos].taskId &&
                    old[oldPos].label == capped[newPos].label &&
                    old[oldPos].packageName == capped[newPos].packageName
        })
        items.clear()
        if (capped.isNotEmpty()) {
            items.addAll(capped)
        }
        diff.dispatchUpdatesTo(this)
        if (items.isNotEmpty()) {
            recyclerView.scrollToPosition(0)
        }
    }

    fun removeItemIfPresent(item: RecentTaskInfo) {
        val index = items.indexOfFirst { it.taskId == item.taskId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    fun currentPackagesTopToBottom(): List<String> =
        items.mapNotNull { it.packageName?.trim()?.takeIf { pkg -> pkg.isNotEmpty() } }

    class ViewHolder(val binding: RecentTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        var compactLayoutApplied: Boolean = false
    }

    inner class ItemTouchHelperCallback : ItemTouchHelper.Callback() {
        private var dragChangedOrder = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val drag = if (stackPane != null) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else {
                0
            }
            return makeMovementFlags(drag, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (stackPane == null) return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (from == to) return true
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
            dragChangedOrder = true
            return true
        }

        override fun isItemViewSwipeEnabled(): Boolean = true

        override fun isLongPressDragEnabled(): Boolean = stackPane != null

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.55f

        override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 3.5f

        override fun getSwipeVelocityThreshold(defaultValue: Float): Float = defaultValue * 2f

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos !in items.indices) return
            val item = items[pos]
            items.removeAt(pos)
            notifyItemRemoved(pos)
            when (stackPane) {
                null -> when (direction) {
                    ItemTouchHelper.RIGHT -> host.onPhoneSwipeRight(item)
                    ItemTouchHelper.LEFT -> host.onPhoneSwipeLeft(item)
                }
                SplitPane.PRIMARY -> when (direction) {
                    ItemTouchHelper.LEFT -> host.onPrimarySwipeLeft(item)
                    ItemTouchHelper.RIGHT -> host.onPrimarySwipeRight(item)
                }
                SplitPane.SECONDARY -> when (direction) {
                    ItemTouchHelper.LEFT -> host.onSecondarySwipeLeft(item)
                    ItemTouchHelper.RIGHT -> host.onSecondarySwipeRight(item)
                }
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragChangedOrder = false
                viewHolder?.itemView?.animate()?.cancel()
                viewHolder?.itemView?.scaleX = 1.06f
                viewHolder?.itemView?.scaleY = 1.06f
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().cancel()
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
            val pane = stackPane
            if (pane != null && dragChangedOrder && items.isNotEmpty()) {
                host.onReorderPaneStack(pane, currentPackagesTopToBottom())
            }
            dragChangedOrder = false
        }
    }
}
