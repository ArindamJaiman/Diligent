package dev.diligent.app.tracking

import dev.diligent.app.data.local.entity.Activity

/** Private activities show as dots wherever the phone may be seen locked: widgets, lock screen. */
object Privacy {
    const val MASK = "• • • •"

    fun publicName(activity: Activity): String = if (activity.isPrivate) MASK else activity.name

    fun publicName(name: String, isPrivate: Boolean): String = if (isPrivate) MASK else name
}
