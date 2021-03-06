/*
 * Created by @UnbarredStream on 08/04/22 17:11
 * Copyright (c) 2022 . All rights reserved.
 * Last modified 08/04/22 17:10
 */

package knf.hydra.core.models

import android.os.Parcelable
import knf.hydra.core.models.data.Category
import knf.hydra.core.models.data.LayoutType
import kotlinx.parcelize.Parcelize

@Parcelize
/** @suppress */
data class InfoModelMin(
    var id: Int = 0,
    var name: String = "",
    var link: String = "",
    var category: Category = Category.UNKNOWN,
    var layoutType: LayoutType = LayoutType.UNKNOWN,
    var coverImage: String? = null
): Parcelable