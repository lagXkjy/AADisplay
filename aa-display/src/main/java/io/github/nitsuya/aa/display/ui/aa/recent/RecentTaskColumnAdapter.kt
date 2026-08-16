package io.github.nitsuya.aa.display.ui.aa.recent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.RecentTaskBinding
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.PaneAppStack
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * Recent-task column for phone / primary VD / secondary VD.
 *
 * Layout: `[Primary(VD1) | Secondary(VD2) | Phone]`.
 * - Phone LEFT → focused VD pane (tap a VD column first to choose VD1/VD2)
 * - VD RIGHT → phone; Primary LEFT → remove; Secondary LEFT → Primary
 * - VD columns: long-press drag reorders; tap / drag-to-index-0 brings to front
 * - VD items sized so [PaneAppStack.MAX_PER_PANE] fit without scrolling
 */
class RecentTaskColumnAdapter(
      private val recyclerView: RecyclerView,
    /** null = phone stack; [SplitPane.PRIMARY] / [SplitPane.SECONDARY] = VD stacks. */
      private val stackPane: Int? = null,
      private val onExit: (() -> Unit),
) : RecyclerView.Adapter<RecentTaskColumnAdapter.ViewHolder>(){

    private val items: MutableList<RecentTaskInfo> = ArrayList()
    private var stackLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    var phoneAdapter: RecentTaskColumnAdapter? = null
    var primaryAdapter: RecentTaskColumnAdapter? = null
    var secondaryAdapter: RecentTaskColumnAdapter? = null

    init {
        ItemTouchHelper(ItemTouchHelperCallback()).attachToRecyclerView(recyclerView)
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
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.ivIcon.setImageBitmap(item.logo)
        holder.binding.tvName.text = "${item.label} [${item.taskId}]"

        if (stackPane != null) {
            applyCompactStackItemLayout(holder)
        }
        // Phone: empty root tap dismisses. VD: whole row (except Close) brings to top.
        if (stackPane == null) {
            holder.binding.root.setOnClickListener { onExit() }
            arrayOf(holder.binding.tvName, holder.binding.ivIcon).forEach {
                it.setOnClickListener { openTask(item) }
            }
            holder.binding.vCard.setOnClickListener(null)
            holder.binding.vCard.isClickable = false
        } else {
            val bringToTop = View.OnClickListener { openTask(item) }
            holder.binding.root.setOnClickListener(bringToTop)
            holder.binding.tvName.setOnClickListener(bringToTop)
            holder.binding.ivIcon.setOnClickListener(bringToTop)
            holder.binding.vCard.setOnClickListener(bringToTop)
        }
        holder.binding.ibClose.setOnClickListener {
            // Stay on the stack panel so multiple tasks can be closed in sequence.
            // removeTask forgets VD ownership so reclaim will not resurrect the closed app.
            removeItem(item)
            CoreApi.removeTask(item.taskId)
        }
    }

    /** Phone launch / VD front-existing; VD also reorders the visible list to top. */
    private fun openTask(item: RecentTaskInfo) {
        if (stackPane != null) {
            moveItemToTop(item)
        }
        val pkg = item.packageName
        if (!pkg.isNullOrBlank()) {
            if (stackPane != null) {
                CoreApi.startActivityOnPane(pkg, 0, stackPane)
            } else {
                CoreApi.startActivity(pkg, 0)
            }
        } else {
            stackPane?.let { CoreApi.setFocusedPane(it) }
            CoreApi.moveTaskToFront(item.taskId)
        }
        onExit()
    }

    private fun moveItemToTop(item: RecentTaskInfo) {
        val from = items.indexOf(item)
        if (from <= 0) return
        items.removeAt(from)
        items.add(0, item)
        notifyItemMoved(from, 0)
        recyclerView.scrollToPosition(0)
    }

    /** Equal-height slots so max stack apps fit in the column without scrolling. */
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
            // Drop portrait 9:16 so height fills the remaining slot under the title row.
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

    fun removeItem(item: RecentTaskInfo){
        val index = items.indexOf(item)
        if (index < 0) return
        this.items.removeAt(index)
        this.notifyItemRemoved(index)
    }

    fun addItem(item: RecentTaskInfo){
        // Cap VD columns at max stack size visually; server evicts the real stack bottom.
        if (stackPane != null && items.size >= PaneAppStack.MAX_PER_PANE) {
            items.removeAt(items.lastIndex)
            notifyItemRemoved(items.size)
        }
        this.items.add(0, item)
        this.notifyItemInserted(0)
        this.recyclerView.scrollToPosition(0)
    }

    fun setItems(items: List<RecentTaskInfo>){
        this.items.clear()
        if(items.isNotEmpty()){
            val capped = if (stackPane != null) {
                items.take(PaneAppStack.MAX_PER_PANE)
            } else {
                items
            }
            this.items.addAll(capped)
        }
        this.notifyDataSetChanged()
        if(this.items.isNotEmpty()){
            this.recyclerView.scrollToPosition(0)
        }
    }

    inner class ViewHolder(val binding: RecentTaskBinding): RecyclerView.ViewHolder(binding.root)

    inner class ItemTouchHelperCallback: ItemTouchHelper.Callback(){
        private var dragChangedOrder = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
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
            target: RecyclerView.ViewHolder
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

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val item = items.get(viewHolder.bindingAdapterPosition)
            removeItem(item)
            when (stackPane) {
                null -> onPhoneSwiped(item, direction)
                SplitPane.PRIMARY -> onPrimarySwiped(item, direction)
                SplitPane.SECONDARY -> onSecondarySwiped(item, direction)
            }
        }

        private fun onPhoneSwiped(item: RecentTaskInfo, direction: Int) {
            if (direction == ItemTouchHelper.RIGHT) {
                CoreApi.removeTask(item.taskId)
                return
            }
            if (direction != ItemTouchHelper.LEFT) return
            // Respect focused pane so phone→VD2 works after selecting the center column.
            val pane = CoreApi.focusedPane.let {
                if (SplitPane.isValid(it)) it else SplitPane.PRIMARY
            }
            val targetAdapter = if (pane == SplitPane.SECONDARY) secondaryAdapter else primaryAdapter
            targetAdapter?.addItem(item)
            CoreApi.moveTaskIdToPane(item.taskId, pane)
        }

        private fun onPrimarySwiped(item: RecentTaskInfo, direction: Int) {
            if (direction == ItemTouchHelper.LEFT) {
                CoreApi.removeTask(item.taskId)
            } else if (direction == ItemTouchHelper.RIGHT) {
                phoneAdapter?.addItem(item)
                CoreApi.moveTaskId(item.taskId, false)
            }
        }

        private fun onSecondarySwiped(item: RecentTaskInfo, direction: Int) {
            if (direction == ItemTouchHelper.LEFT) {
                // Move onto Primary (VD1); use close button to dismiss.
                primaryAdapter?.addItem(item)
                CoreApi.moveTaskIdToPane(item.taskId, SplitPane.PRIMARY)
            } else if (direction == ItemTouchHelper.RIGHT) {
                phoneAdapter?.addItem(item)
                CoreApi.moveTaskId(item.taskId, false)
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragChangedOrder = false
            }
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                viewHolder?.itemView?.apply {
                    ViewCompat.animate(this)
                        .setDuration(200)
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .start()
                }
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.apply {
                ViewCompat.animate(this)
                    .setDuration(200)
                    .scaleX(1f)
                    .scaleY(1f)
                    .start()
            }
            // After drag reorder: bring the new top (index 0) to front — no cold start.
            if (stackPane != null && dragChangedOrder && items.isNotEmpty()) {
                val top = items[0]
                val pkg = top.packageName
                if (!pkg.isNullOrBlank()) {
                    CoreApi.startActivityOnPane(pkg, 0, stackPane)
                } else {
                    CoreApi.setFocusedPane(stackPane)
                    CoreApi.moveTaskToFront(top.taskId)
                }
            }
            dragChangedOrder = false
        }
    }
}
