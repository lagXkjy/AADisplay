package io.github.nitsuya.aa.display.model

import android.os.Parcel
import android.os.Parcelable

data class RecentTask(
    val mainDisplay: List<RecentTaskInfo>,
    val primaryDisplay: List<RecentTaskInfo>,
    val secondaryDisplay: List<RecentTaskInfo>,
) : Parcelable {
    /** Combined virtual-display tasks (primary + secondary). */
    val virtualDisplay: List<RecentTaskInfo>
        get() = primaryDisplay + secondaryDisplay

    constructor(parcel: Parcel) : this(
        parcel.createTypedArrayList(RecentTaskInfo)!!,
        parcel.createTypedArrayList(RecentTaskInfo)!!,
        parcel.createTypedArrayList(RecentTaskInfo)!!,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeTypedList(mainDisplay)
        parcel.writeTypedList(primaryDisplay)
        parcel.writeTypedList(secondaryDisplay)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RecentTask> {
        override fun createFromParcel(parcel: Parcel): RecentTask = RecentTask(parcel)
        override fun newArray(size: Int): Array<RecentTask?> = arrayOfNulls(size)
    }
}
