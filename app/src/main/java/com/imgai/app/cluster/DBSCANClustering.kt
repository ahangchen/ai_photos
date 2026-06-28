package com.imgai.app.cluster

import kotlin.math.sqrt

/**
 * DBSCAN 密度聚类算法
 *
 * - 使用 cosine distance: 1 - cosine_similarity
 * - 不需要预设簇数量，自动发现人数
 * - eps: 同一人的最大距离阈值 (0.4 = cosine similarity > 0.6 视为同一人)
 * - minPts: 形成核心点的最小样本数
 */
object DBSCANClustering {

    data class ClusterResult(
        val labels: IntArray,      // 每个样本的簇 ID，-1 = 噪声
        val clusterCount: Int,     // 有效簇数量
        val clusterSizes: Map<Int, Int>  // 簇ID -> 样本数
    )

    fun cluster(
        embeddings: List<FloatArray>,
        eps: Float = 0.4f,
        minPts: Int = 2
    ): ClusterResult {
        val n = embeddings.size
        if (n == 0) return ClusterResult(IntArray(0), 0, emptyMap())

        val labels = IntArray(n) { -1 }  // -1 = unvisited
        val visited = BooleanArray(n)
        val clusterId = intArrayOf(0)

        // 预计算距离矩阵 (n 小于几百时可行)
        val distMatrix = precomputeDistances(embeddings)

        for (i in 0 until n) {
            if (visited[i]) continue
            visited[i] = true

            val neighbors = regionQuery(i, distMatrix, eps)

            if (neighbors.size < minPts) {
                // 噪声点（后面可能被重新标记为边界点）
                labels[i] = -1
            } else {
                // 扩展簇
                val cid = clusterId[0]++
                labels[i] = cid
                expandCluster(
                    cid, neighbors, visited, labels,
                    distMatrix, eps, minPts
                )
            }
        }

        // 统计
        val sizes = mutableMapOf<Int, Int>()
        for (label in labels) {
            if (label >= 0) {
                sizes[label] = sizes.getOrDefault(label, 0) + 1
            }
        }

        return ClusterResult(labels, clusterId[0], sizes)
    }

    private fun expandCluster(
        clusterId: Int,
        initialNeighbors: List<Int>,
        visited: BooleanArray,
        labels: IntArray,
        distMatrix: Array<FloatArray>,
        eps: Float,
        minPts: Int
    ) {
        val queue = ArrayDeque(initialNeighbors)

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()

            if (!visited[idx]) {
                visited[idx] = true
                val moreNeighbors = regionQuery(idx, distMatrix, eps)

                if (moreNeighbors.size >= minPts) {
                    queue.addAll(moreNeighbors)
                }
            }

            if (labels[idx] == -1 || labels[idx] == 0 && visited[idx]) {
                labels[idx] = clusterId
            }
        }
    }

    private fun regionQuery(
        idx: Int,
        distMatrix: Array<FloatArray>,
        eps: Float
    ): List<Int> {
        val neighbors = mutableListOf<Int>()
        for (j in distMatrix[idx].indices) {
            if (distMatrix[idx][j] <= eps) {
                neighbors.add(j)
            }
        }
        return neighbors
    }

    private fun precomputeDistances(embeddings: List<FloatArray>): Array<FloatArray> {
        val n = embeddings.size
        val matrix = Array(n) { FloatArray(n) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dist = cosineDistance(embeddings[i], embeddings[j])
                matrix[i][j] = dist
                matrix[j][i] = dist
            }
            matrix[i][i] = 0f
        }

        return matrix
    }

    /** Cosine distance = 1 - cosine_similarity */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }
        val similarity = if (normA > 0 && normB > 0) {
            dot / (sqrt(normA) * sqrt(normB))
        } else {
            0.0
        }
        return (1.0 - similarity).toFloat()
    }
}
