package zenith.core

/**
 * Binary min-heap with the same sift rules as java.util.PriorityQueue, so equal keys come out in
 * the same order on the JVM and on iOS (where java.util does not exist).
 */
internal class MinHeap<T>(private val comparator: Comparator<in T>) {
    private val items = ArrayList<T>()

    fun isNotEmpty() = items.isNotEmpty()

    operator fun plusAssign(item: T) {
        items.add(item)
        var k = items.size - 1
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val p = items[parent]
            if (comparator.compare(item, p) >= 0) break
            items[k] = p
            k = parent
        }
        items[k] = item
    }

    /** Removes and returns the smallest item; the heap must not be empty. */
    fun poll(): T {
        val result = items[0]
        val last = items.removeAt(items.size - 1)
        val n = items.size
        if (n > 0) {
            var k = 0
            val half = n ushr 1
            while (k < half) {
                var child = 2 * k + 1
                var c = items[child]
                val right = child + 1
                if (right < n && comparator.compare(c, items[right]) > 0) {
                    child = right
                    c = items[child]
                }
                if (comparator.compare(last, c) <= 0) break
                items[k] = c
                k = child
            }
            items[k] = last
        }
        return result
    }
}
