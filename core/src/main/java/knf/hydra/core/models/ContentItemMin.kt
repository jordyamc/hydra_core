package knf.hydra.core.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** @suppress */
@Parcelize
data class ContentItemMin(
    var id: Int = 0,
    var name: String = "",
    var number: Double = 0.0,
    var link: String = "",
    var thumbnailLink: String? = null
): Parcelable