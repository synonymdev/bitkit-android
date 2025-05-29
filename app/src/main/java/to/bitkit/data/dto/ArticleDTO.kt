package to.bitkit.data.dto

data class ArticleDTO(
    val title: String,
    val publishedDate: String,
    val link: String,
    val publisherDTO: PublisherDTO
)
