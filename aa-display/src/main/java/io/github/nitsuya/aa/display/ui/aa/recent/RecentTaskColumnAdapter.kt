package io.github.nitsuya.aa.display.ui.aa.recent

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.RecentTaskBinding
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * Recent-task column for phone / primary VD / secondary VD.
 *
 * Layout: `[Primary(VD1) | Secondary(VD2) | Phone]`.
 * - Phone LEFT → focused VD pane (tap a VD column first to choose VD1/VD2)
 * - VD RIGHT → phone; Primary LEFT → remove; Secondary LEFT → Primary
 */
class RecentTaskColumnAdapter(
      private val recyclerView: RecyclerView,
    /** null = phone stack; [SplitPane.PRIMARY] / [SplitPane.SECONDARY] = VD stacks. */
      private val stackPane: Int? = null,
      private val onExit: (() -> Unit),
) : RecyclerView.Adapter<RecentTaskColumnAdapter.ViewHolder>(){

    private val items: MutableList<RecentTaskInfo> = ArrayList()

    var phoneAdapter: RecentTaskColumnAdapter? = null
    var primaryAdapter: RecentTaskColumnAdapter? = null
    var secondaryAdapter: RecentTaskColumnAdapter? = null

    init {
        ItemTouchHelper(ItemTouchHelperCallback()).attachToRecyclerView(recyclerView)
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
            ConstraintSet().apply {
                clone(holder.binding.clItem)
                constrainPercentWidth(R.id.v_card, 0.8f)
                setDimensionRatio(R.id.v_card, "W,9:16")
                applyTo(holder.binding.clItem)
            }
        }
        holder.binding.root.setOnClickListener {
            onExit()
        }
        // Icon/title open the task; gray card only hosts Close (avoids mis-taps on car UI).
        arrayOf(holder.binding.tvName, holder.binding.ivIcon).forEach {
            it.setOnClickListener {
                val pkg = item.packageName
                // Phone-stack tap: launch onto the focused AA split pane.
                // VD-stack tap: focus that pane and bring the task forward.
                if (stackPane == null && !pkg.isNullOrBlank()) {
                    CoreApi.startActivity(pkg, 0)
                } else {
                    stackPane?.let { CoreApi.setFocusedPane(it) }
                    CoreApi.moveTaskToFront(item.taskId)
                }
                onExit()
            }
        }
        holder.binding.ibClose.setOnClickListener {
            // Stay on the stack panel so multiple tasks can be closed in sequence.
            // removeTask forgets VD ownership so reclaim will not resurrect the closed app.
            removeItem(item)
            CoreApi.removeTask(item.taskId)
        }
    }

    override fun getItemCount(): Int = items.size

    fun removeItem(item: RecentTaskInfo){
        val index = items.indexOf(item)
        this.items.removeAt(index)
        this.notifyItemRemoved(index)
    }

    fun addItem(item: RecentTaskInfo){
        this.items.add(0, item)
        this.notifyItemInserted(0)
        this.recyclerView.scrollToPosition(0)
    }

    fun setItems(items: List<RecentTaskInfo>){
        this.items.clear()
        if(items.isNotEmpty()){
            this.items.addAll(items)
        }
        this.notifyDataSetChanged()
        if(this.items.isNotEmpty()){
            this.recyclerView.scrollToPosition(0)
        }
    }

    inner class ViewHolder(val binding: RecentTaskBinding): RecyclerView.ViewHolder(binding.root) {}

    inner class ItemTouchHelperCallback: ItemTouchHelper.Callback(){
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = true

        override fun isLongPressDragEnabled(): Boolean = true

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val item = items.get(viewHolder.layoutPosition)
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
            // Previously always forced Primary/VD1.
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
        }

    }

}
