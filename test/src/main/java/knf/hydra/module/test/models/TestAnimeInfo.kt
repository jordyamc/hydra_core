package knf.hydra.module.test.models

import androidx.annotation.Keep
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.*
import knf.hydra.core.models.ChaptersData
import knf.hydra.core.models.InfoModel
import knf.hydra.core.models.data.*
import knf.hydra.core.tools.ModulePreferences
import knf.hydra.module.test.repository.ChaptersSource
import knf.hydra.module.test.retrofit.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import pl.droidsonroids.jspoon.ElementConverter
import pl.droidsonroids.jspoon.annotation.Selector
import java.net.URL
import java.net.URLEncoder
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

@Entity
@TypeConverters(InfoModel.Converters::class)
class TestAnimeInfo : InfoModel() {
    @PrimaryKey
    @Selector(".Strs.RateIt", attr = "data-id")
    override var id: Int = Random.nextInt()

    @Selector("h1.Title")
    override var name: String = "???"

    @Selector("link[rel=canonical]", attr = "href")
    override var link: String = ""

    override var category: Category = Category.ANIME

    @Selector("div.Image img", attr = "abs:src")
    override var coverImage: String? = null

    @Selector("div.Description")
    override var description: String? = null

    @Selector("nav.Nvgnrs", converter = GenresConverter::class)
    override var genres: List<Tag>? = null

    @Embedded(prefix = "ranking_")
    @Selector("div.Votes", converter = RankingConverter::class)
    override var ranking: RankingData? = null

    @Ignore
    @Selector(":root", converter = RelatedConverter::class)
    override var related: List<Related>? = null

    /*@Ignore
    @Selector(":root", converter = MusicConverter::class)
    override var music: Flow<List<Music>?>? = null*/

    @Embedded(prefix = "state_")
    @Selector(":root", converter = StateConverter::class)
    override var state: StateData? = null

    @Selector("span.Type")
    override var type: String? = null

    @Ignore
    @Selector(":root", converter = ChaptersConverter::class)
    override var chaptersData: ChaptersData? = null

    @Embedded(prefix = "data_")
    @Selector(":root", converter = ExtraDataConverter::class)
    override var extraSections: List<ExtraSection> = emptyList()

    @Keep
    class GenresConverter @Keep constructor() : ElementConverter<List<Tag>> {
        override fun convert(node: Element, selector: Selector): List<Tag> {
            return node.select("a").map {
                Tag(
                    name = it.text(),
                    payload = it.attr("href").substringAfterLast("="),
                    tagListEnabled = true
                )
            }
        }
    }

    @Keep
    class RankingConverter @Keep constructor() : ElementConverter<RankingData?> {
        override fun convert(node: Element, selector: Selector): RankingData? {
            return try {
                val stars = node.select("span#votes_prmd").text().toDouble()
                val votes = node.select("span#votes_nmbr").text().toInt()
                RankingData(stars, votes)
            } catch (e: Exception) {
                null
            }
        }
    }

    @Keep
    class RelatedConverter @Keep constructor() : ElementConverter<List<Related>?> {
        override fun convert(node: Element, selector: Selector): List<Related>? {
            val relatedList = node.select("ul.ListAnmRel")
            if (relatedList.isEmpty()) {
                return null
            }
            val rels = relatedList.select("li")
            val links = rels.map { it.select("a").attr("abs:href") }
            val objs =
                links.map { NetworkRepository.getRelatedInfo(it, NetworkRepository.currentBypass) }
            rels.map { it.ownText().trim().removeSurrounding("(", ")") }
                .forEachIndexed { index, relation ->
                    objs[index]?.let {
                        it.link = links[index]
                        it.relation = relation
                    }
                }
            return objs.filterNotNull()
        }
    }

    @Keep
    class StateConverter @Keep constructor() : ElementConverter<StateData> {
        override fun convert(node: Element, selector: Selector): StateData {
            val animeState = node.select("p.AnmStts").first()
            val status =
                if (animeState.hasClass("A")) StateData.Type.COMPLETED else StateData.Type.EMISSION
            var emissionDay: StateData.EmissionDay? = null
            if (status == StateData.Type.EMISSION) {
                val html = node.html()
                val info =
                    "anime_info = \\[(.*)\\];".toRegex().find(html)?.destructured?.component1()
                        ?.split(",")?.map { it.replace("\"", "") }
                if (info?.size == 4) {
                    val calendar = Calendar.getInstance().apply {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(info.last())
                            ?.let {
                                time = it
                            }
                    }
                    emissionDay =
                        when (calendar.get(Calendar.DAY_OF_WEEK)) {
                            2 -> StateData.EmissionDay.MONDAY
                            3 -> StateData.EmissionDay.TUESDAY
                            4 -> StateData.EmissionDay.WEDNESDAY
                            5 -> StateData.EmissionDay.THURSDAY
                            6 -> StateData.EmissionDay.FRIDAY
                            7 -> StateData.EmissionDay.SATURDAY
                            1 -> StateData.EmissionDay.SUNDAY
                            else -> null
                        }
                }
            }
            return StateData(status, emissionDay)
        }
    }

    @Keep
    class ExtraDataConverter @Keep constructor() : ElementConverter<List<ExtraSection>> {
        fun <T>takeTime(onTime: (Long) -> Unit, block: () -> T): T {
            val start = System.currentTimeMillis()
            val result = block()
            onTime(System.currentTimeMillis() - start)
            return result
        }
        override fun convert(node: Element, selector: Selector): List<ExtraSection> {
            val sections = mutableListOf<ExtraSection>()
            val isBasicDataEnabled = runBlocking { ModulePreferences.getPreference("mal_basic_data", false) }
            val isStaffEnabled = runBlocking { ModulePreferences.getPreference("mal_staff", false) }
            val isGalleryEnabled = runBlocking { ModulePreferences.getPreference("mal_gallery", false) }
            val isMusicEnabled = runBlocking { ModulePreferences.getPreference("mal_music", false) }
            if (!listOf(isBasicDataEnabled, isGalleryEnabled, isStaffEnabled, isMusicEnabled).any { it }){
                return sections
            }
            val title = node.select("h1.Title").text()
            val names = node.select(".TxtAlt").joinToString("\n") { it.text().trim() }
            if (names.isNotBlank()) {
                sections.add(
                    ExtraSection(
                        "Nombres alternativos",
                        TextData(names, ClickAction.Clipboard(names))
                    )
                )
            }
            try {
                val searchLink = "https://api.jikan.moe/v3/search/anime?q=${URLEncoder.encode(title, "utf-8")}&limit=1"
                val searchResponseJson = runBlocking(Dispatchers.IO) {
                    withTimeout(2000) {
                        JSONObject(URL(searchLink).readText())
                    }
                }
                val searchResults = searchResponseJson.getJSONArray("results")
                if (searchResults.length() > 0) {
                    val result = searchResults.getJSONObject(0)
                    val id = result.getInt("mal_id")
                    if (isBasicDataEnabled) {
                        try {
                            runBlocking(Dispatchers.IO) {
                                withTimeout(2000) {
                                    val info = JSONObject(URL("https://api.jikan.moe/v3/anime/$id").readText())
                                    val aired = info.getJSONObject("aired")
                                    val type = info.getString("type")
                                    if (type == "Movie") {
                                        sections.add(ExtraSection("Emisión", TextData(aired.getString("string"))))
                                    } else {
                                        sections.add(ExtraSection("Duración", TextData(aired.getString("string"))))
                                    }
                                    sections.add(ExtraSection("Trailer", YoutubeData(info.getString("trailer_url").substringAfterLast("embed/").substringBeforeLast("?"))))

                                }
                            }
                        } catch (e:Exception){
                            e.printStackTrace()
                        }
                    }
                    if (isStaffEnabled) {
                        try {
                            val imageOrNull: (String) -> ImageItem? = {
                                if (it.contains("questionmark_23.gif"))
                                    null
                                else
                                    VerticalImageItem(it)
                            }
                            runBlocking(Dispatchers.IO) {
                                withTimeout(2000) {
                                    val charStaff = JSONObject(URL("https://api.jikan.moe/v3/anime/$id/characters_staff").readText())
                                    try {
                                        val characters = charStaff.getJSONArray("characters")
                                        val charactersList = mutableListOf<CollectionItem>()
                                        for (i in 0 until characters.length()) {
                                            val character = characters.getJSONObject(i)
                                            charactersList.add(
                                                CollectionItem(
                                                    character.getString("name"),
                                                    character.getString("role"),
                                                    imageOrNull(character.getString("image_url")),
                                                    ClickAction.Web(character.getString("url"))
                                                )
                                            )
                                        }
                                        sections.add(ExtraSection("Personajes", CollectionData(charactersList)))
                                    }catch (e:Exception){
                                        e.printStackTrace()
                                    }
                                    try {
                                        val staff = charStaff.getJSONArray("staff")
                                        val staffList = mutableListOf<CollectionItem>()
                                        for (i in 0 until staff.length()) {
                                            val character = staff.getJSONObject(i)
                                            staffList.add(
                                                CollectionItem(
                                                    character.getString("name"),
                                                    character.getJSONArray("positions").getString(0),
                                                    imageOrNull(character.getString("image_url")),
                                                    ClickAction.Web(character.getString("url"))
                                                )
                                            )
                                        }
                                        sections.add(ExtraSection("Staff", CollectionData(staffList)))
                                    }catch (e:Exception){
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }catch (e:Exception){
                            e.printStackTrace()
                        }
                    }
                    if (isGalleryEnabled) {
                        val galleryList = mutableListOf<ImageItem>()
                        try {
                            runBlocking(Dispatchers.IO) {
                                withTimeout(2000) {
                                    val videos = JSONObject(URL("https://api.jikan.moe/v3/anime/$id/videos").readText()).getJSONArray("promo")
                                    for (i in 0 until videos.length()){
                                        galleryList.add(YoutubeItem(videos.getJSONObject(i).getString("video_url").substringAfterLast("embed/").substringBeforeLast("?")))
                                    }
                                }
                            }

                        }catch (e:Exception) {
                            e.printStackTrace()
                        }
                        try {
                            runBlocking(Dispatchers.IO) {
                                withTimeout(2000) {
                                    val pictures = JSONObject(URL("https://api.jikan.moe/v3/anime/$id/pictures").readText()).getJSONArray("pictures")
                                    for (i in 0 until pictures.length()) {
                                        galleryList.add(VerticalImageItem(pictures.getJSONObject(i).getString("large")))
                                    }
                                }
                            }

                        }catch (e:Exception){
                            e.printStackTrace()
                        }
                        if (galleryList.isNotEmpty()){
                            sections.add(ExtraSection("Galería", GalleryData(galleryList)))
                        }
                    }
                    if (isMusicEnabled) {
                        try {
                            runBlocking(Dispatchers.IO) {
                                withTimeout(2000) {
                                    val music = JSONObject(URL("https://anusic-api.herokuapp.com/api/v1/anime/$id").readText()).getJSONObject("data").getJSONArray("collections")
                                    val musicList = mutableListOf<Music>()
                                    for (a in 0 until music.length()) {
                                        val themes = music.getJSONObject(a).getJSONArray("themes")
                                        for (b in 0 until themes.length()) {
                                            val theme = themes.getJSONObject(b)
                                            val name = theme.getString("name")
                                            val type = theme.getInt("type")
                                            val typeName = if (type == 0) "OP" else "ED"
                                            val link = theme.getJSONArray("sources").getJSONObject(0).getString("link")
                                            musicList.add(Music(name, link, typeName))
                                        }
                                    }
                                    if (musicList.isNotEmpty()) {
                                        sections.add(ExtraSection("Música", MusicData(musicList)))
                                    }
                                }
                            }

                        }catch (e:Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return sections
        }
    }

    @Keep
    class MusicConverter @Keep constructor() : ElementConverter<Flow<List<Music>?>?> {
        override fun convert(node: Element, selector: Selector): Flow<List<Music>?> {
            return flow {
                try {
                    /*val title = node.select("h1.Title").text()
                    val search = withContext(Dispatchers.IO) {
                        Jsoup.connect("https://www.reddit.com/r/AnimeThemes/wiki/anime_index").get()
                    }.select("a:containsOwn($title)")
                    if (search.isEmpty()) {
                        emit(null)
                        return@flow
                    }
                    val selected = if (search.size == 1) {
                        search.first()
                    } else {
                        search.minByOrNull {
                            abs(it.text().substringBefore("(").trim().length - title.length)
                        }!!
                    }
                    val link = selected.attr("abs:href")
                    Log.e("Music","Found: $link")
                    val id = link.substringAfterLast("#")
                    Log.e("Music","Search id: $id")
                    val animeSearch = withContext(Dispatchers.IO) { Jsoup.connect(link).get() }
                    val animeHeader = animeSearch.select("h3#$id").first()
                    if (animeHeader == null) {
                        emit(null)
                        return@flow
                    }
                    var songsTable = animeHeader.nextElementSibling()
                    if (songsTable.tagName() == "p")
                        songsTable = songsTable.nextElementSibling()
                    val songs = songsTable.select("tbody tr")
                    val songList = mutableListOf<Music>()
                    songs.forEach {
                        val regex = "(.*) \"(.*)\"".toRegex().find(it.select("td").first().text())
                        if (regex != null) {
                            val (sSub, sTitle) = regex.destructured
                            val sLink = it.select("td a").first().attr("href")
                            val response =
                                Jsoup.connect(sLink).ignoreContentType(true).followRedirects(false)
                                    .execute()
                            if (response.statusCode() != 200)
                                songList.add(Music(sTitle, sLink, sSub))
                        }
                    }
                    val result = if (songList.isNotEmpty())
                        songList
                    else
                        null
                    emit(result)*/
                    val title = node.select("h1.Title").text()
                    val searchJson = withContext(Dispatchers.IO) {
                        //URL("https://themes.moe/api/anime/search/$title").readText()
                        Jsoup.connect("https://themes.moe/api/anime/search/$title")
                            .ignoreContentType(true)
                            .method(Connection.Method.GET)
                            .timeout(2000)
                            .execute().body()
                    }
                    if (JSONArray(searchJson).length() == 0) {
                        emit(null)
                        return@flow
                    }
                    val songsJson = withContext(Dispatchers.IO) {
                        Jsoup.connect("https://themes.moe/api/themes/search")
                            .ignoreContentType(true)
                            .method(Connection.Method.POST)
                            .requestBody(searchJson)
                            .timeout(2000)
                            .execute().body()
                    }
                    val songsArray = JSONArray(songsJson)
                    if (songsArray.length() == 0) {
                        emit(null)
                        return@flow
                    }
                    val result = songsArray.getJSONObject(0).getJSONArray("themes")
                    val songList = mutableListOf<Music>()
                    for (index in 0 until result.length()) {
                        val json = result.getJSONObject(index)
                        songList.add(
                            Music(
                                json.getString("themeName"),
                                json.getJSONObject("mirror").getString("mirrorURL"),
                                json.getString("themeType")
                            )
                        )
                    }
                    if (songList.isNotEmpty())
                        emit(songList)
                    else
                        emit(null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emit(null)
                }
            }
        }
    }

    @Keep
    class ChaptersConverter @Keep constructor() : ElementConverter<ChaptersData?> {
        override fun convert(node: Element, selector: Selector): ChaptersData? {
            val isMovie = node.select("span.Type").first().classNames().contains("movie")
            val id = node.select(".Strs.RateIt").attr("data-id")
            val link = node.select("link[rel=canonical]").attr("href")
            val chapLinkBase = link.replace("/anime/", "/ver/") + "-"
            val thumbLinkBase = "https://cdn.animeflv.net/screenshots/$id/%s/th_3.jpg"
            val data = "episodes = \\[\\[(.*)\\]\\];".toRegex()
                .find(node.html())?.destructured?.component1()
            val chapList = data?.let { d ->
                d.split("],[").map { it.substringBeforeLast(",") }
            } ?: return null
            return if (isMovie) {
                val chapter = chapList.first()
                val formatted = DecimalFormat("0.#").format(chapter.toDouble())
                ChaptersData.Single(
                    TestChapterModel(
                        id,
                        link,
                        chapLinkBase + formatted,
                        chapter.toDouble(),
                        String.format(thumbLinkBase, formatted),
                        null
                    )
                )
            } else {
                val disqusVersion =
                    Regex("load\\.(\\w+)\\.js").find(URL("https://https-animeflv-net.disqus.com/embed.js").readText())?.destructured?.component1()
                ChaptersData.Multiple(Pager(
                    config = PagingConfig(
                        pageSize = 10,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = {
                        ChaptersSource(
                            disqusVersion,
                            ChapterConstructor(
                                id,
                                link,
                                chapLinkBase,
                                thumbLinkBase,
                                chapList
                            ), NetworkRepository.currentDbBridge
                        )
                    }
                ).flow)
            }
        }
    }

    data class ChapterConstructor(
        val seriesId: String,
        val seriesLink: String,
        val chapterLinkBase: String,
        val thumbLinkBase: String,
        val chapterList: List<String>
    )
}