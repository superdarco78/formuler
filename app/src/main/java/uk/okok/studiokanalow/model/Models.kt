package uk.okok.studiokanalow.model

data class Category(
    val id: String,
    val name: String
)

data class Channel(
    val streamId: String,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val num: Int
)
