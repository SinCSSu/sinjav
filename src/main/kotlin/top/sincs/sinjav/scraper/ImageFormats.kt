package top.sincs.sinjav.scraper

/** 图片格式魔数检测（JPEG/PNG/GIF/WEBP/AVIF/BMP），用于上传前的真实 Content-Type 判定 */
object ImageFormats {

    /** 返回 MIME 类型，未知返回 null */
    fun detect(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> "image/avif"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }

    /** Emby 图片上传不支持 WebP/AVIF（服务端会返回 500） */
    fun isEmbyUnsupported(type: String?): Boolean = type == "image/webp" || type == "image/avif"
}
