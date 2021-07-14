package knf.hydra.core.models

import androidx.recyclerview.widget.DiffUtil
import knf.hydra.core.models.data.RankingData

abstract class DirectoryModel {
    abstract var id: Int
    abstract var name: String
    abstract var seriesLink: String
    open var type: String? = null
    open var imageLink: String? = null
    open var rankingData: RankingData? = null

    companion object{
        val DIFF = object : DiffUtil.ItemCallback<DirectoryModel>(){
            override fun areItemsTheSame(p0: DirectoryModel, p1: DirectoryModel): Boolean =
                p0.id == p1.id

            override fun areContentsTheSame(p0: DirectoryModel, p1: DirectoryModel): Boolean =
                p0.rankingData?.stars == p1.rankingData?.stars
        }
    }
}