package top.sincs.sinjav.api.dto

data class PageDto<T>(val page: Int, val size: Int, val total: Long, val totalPages: Int, val items: List<T>) {
    companion object {
        fun <T> of(page: Int, size: Int, total: Long, items: List<T>) = PageDto(
            page = page, size = size, total = total,
            totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt(),
            items = items,
        )
    }
}
