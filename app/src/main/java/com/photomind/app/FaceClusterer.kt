package com.photomind.app

import kotlin.math.sqrt

/** Simple local incremental clustering of FaceNet embeddings. */
class FaceClusterer(private val database: PhotoDatabase) {
    private data class State(
        val id: Long,
        var centroid: FloatArray,
        var sampleCount: Int
    )

    private val clusters = database.loadClusterVectors()
        .map { State(it.id, it.centroid, it.sampleCount) }
        .toMutableList()

    fun assignPhoto(photoId: Long, photoUri: String, embeddings: List<FloatArray>, complete: Boolean) {
        val assignments = mutableListOf<Pair<Long, FloatArray>>()
        embeddings.forEach { embedding ->
            val cluster = nearest(embedding)
            if (cluster != null && euclidean(cluster.centroid, embedding) <= MATCH_THRESHOLD) {
                val newCount = cluster.sampleCount + 1
                val merged = FloatArray(embedding.size) { i ->
                    ((cluster.centroid[i] * cluster.sampleCount) + embedding[i]) / newCount.toFloat()
                }
                cluster.centroid = normalize(merged)
                cluster.sampleCount = newCount
                database.updateClusterCentroid(cluster.id, cluster.centroid, newCount)
                assignments += cluster.id to embedding
            } else {
                val normalized = normalize(embedding)
                val id = database.createCluster(normalized, photoUri)
                if (id > 0) {
                    clusters += State(id, normalized, 1)
                    assignments += id to embedding
                }
            }
        }
        database.replacePhotoFaces(photoId, assignments, complete)
    }

    private fun nearest(vector: FloatArray): State? = clusters.minByOrNull { euclidean(it.centroid, vector) }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum).toFloat()
    }

    private fun normalize(values: FloatArray): FloatArray {
        var sum = 0.0
        values.forEach { sum += it * it }
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(values.size) { values[it] / norm }
    }

    companion object {
        // Conservative starting point. The user can correct split clusters by naming them alike.
        private const val MATCH_THRESHOLD = 0.88f
    }
}
