package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ArticleDTO(
    val title: String,
    val publishedDate: String,
    val link: String,
    val publisher: PublisherDTO
)
