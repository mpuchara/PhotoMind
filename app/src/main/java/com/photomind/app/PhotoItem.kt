package com.photomind.app

data class PhotoItem(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val bucket: String,
    val dateTaken: Long,
    val dateModified: Long,
    val labels: String,
    val ocr: String,
    var userTags: String,
    val sceneDescription: String = "",
    val people: String = "",
    val faceCount: Int = 0
)
