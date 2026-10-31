package io.github.grahamfairbank.commorpheus

import java.util.concurrent.ConcurrentHashMap

/**
 * A small time-limited memo, so a tab's render can reuse the Morpheus record its show() loaded a moment
 * earlier on the same page load (Morpheus calls show() and then renderTemplate() back to back).
 *
 * Holds data only, never a show() decision: every show() still makes its own permission and tenant checks.
 * Bounded: expired entries are dropped first, then the oldest.
 */
class ShortMemo<K, V> {

	final long ttlMs
	final int maxEntries
	private final Closure<Long> clock
	private final Map<K, Entry<V>> entries = new ConcurrentHashMap<K, Entry<V>>()

	private static class Entry<V> {
		final long at
		final V value
		Entry(long at, V value) { this.at = at; this.value = value }
	}

	ShortMemo(long ttlMs, int maxEntries, Closure<Long> clock = { System.currentTimeMillis() }) {
		this.ttlMs = ttlMs
		this.maxEntries = maxEntries
		this.clock = clock
	}

	/** The value stored for key less than ttlMs ago, or null. */
	V get(K key) {
		if (key == null) return null
		Entry<V> e = entries.get(key)
		if (e == null) return null
		if (clock.call() - e.at >= ttlMs) {
			entries.remove(key, e)
			return null
		}
		return e.value
	}

	void put(K key, V value) {
		if (key == null || value == null) return
		entries.put(key, new Entry<V>(clock.call(), value))
		if (entries.size() <= maxEntries) return
		synchronized (entries) {
			if (entries.size() <= maxEntries) return
			long now = clock.call()
			entries.entrySet().removeIf { Map.Entry<K, Entry<V>> x -> now - x.value.at >= ttlMs }
			int over = entries.size() - maxEntries
			if (over > 0) {
				List<Map.Entry<K, Entry<V>>> oldest = new ArrayList<Map.Entry<K, Entry<V>>>(entries.entrySet())
				oldest.sort { Map.Entry<K, Entry<V>> x -> x.value.at }
				oldest.take(over).each { Map.Entry<K, Entry<V>> x -> entries.remove(x.key) }
			}
		}
	}

	void clear() { entries.clear() }

	int size() { entries.size() }
}
