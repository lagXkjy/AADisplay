package io.github.nitsuya.aa.display.model

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable

data class RecentTaskInfo(
    var logo: Bitmap?,
    var taskId: Int,
    var label: String?,
    var snapshot: Bitmap?,
    var packageName: String? = null
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(Bitmap::class.java.classLoader),
        parcel.readInt(),
        parcel.readString(),
        parcel.readParcelable(Bitmap::class.java.classLoader),
        parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(logo, flags)
        parcel.writeInt(taskId)
        parcel.writeString(label)
        parcel.writeParcelable(snapshot, flags)
        parcel.writeString(packageName)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RecentTaskInfo> {
        override fun createFromParcel(parcel: Parcel): RecentTaskInfo {
            return RecentTaskInfo(parcel)
        }

        override fun newArray(size: Int): Array<RecentTaskInfo?> {
            return arrayOfNulls(size)
        }
    }
}
