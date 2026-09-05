package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.DroppedMarker
import uz.disastrouspumpkin.wdb.protocol.LogEvent
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.RunBoundary
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Captures the app's stdout/stderr, keeps a bounded in-memory tail of the current
 * and previous run (design D12), and fans events out to subscribers. A slow
 * subscriber never blocks the app: its queue drops oldest and a [DroppedMarker]
 * records the gap (design D21).
 */
class LogHub(
    private val maxLinesPerRun: Int = 500,
    private val subscriberQueueCap: Int = 1000,
) {
    private val lock = ReentrantLock()
    private var runId = 0L
    private val currentRun = ArrayDeque<LogEvent>()
    private val previousRun = ArrayDeque<LogEvent>()
    private val subscribers = CopyOnWriteArrayList<LogSubscriber>()

    /** Begin a new run: rotate history, emit and record a run boundary. */
    fun beginRun(): Long = lock.withLock {
        previousRun.clear()
        previousRun.addAll(currentRun)
        currentRun.clear()
        runId++
        val boundary = RunBoundary(runId, now())
        record(boundary)
        broadcast(boundary)
        runId
    }

    fun stdout(text: String) = line(LogStream.STDOUT, text)
    fun stderr(text: String) = line(LogStream.STDERR, text)

    private fun line(stream: LogStream, text: String) {
        val event = LogLine(stream, now(), text)
        lock.withLock {
            record(event)
            broadcast(event)
        }
    }

    private fun record(event: LogEvent) {
        currentRun.addLast(event)
        while (currentRun.size > maxLinesPerRun) currentRun.removeFirst()
    }

    private fun broadcast(event: LogEvent) {
        for (s in subscribers) s.offer(event)
    }

    /** Register a subscriber, returning it together with the history to send first. */
    fun subscribe(): Pair<LogSubscriber, List<LogEvent>> = lock.withLock {
        val history = ArrayList<LogEvent>(previousRun.size + currentRun.size)
        history.addAll(previousRun)
        history.addAll(currentRun)
        val sub = LogSubscriber(subscriberQueueCap)
        subscribers.add(sub)
        sub to history
    }

    fun unsubscribe(sub: LogSubscriber) {
        subscribers.remove(sub)
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * One consumer's bounded view of the log stream. [offer] never blocks the
 * producer; when the queue is full it drops the oldest event and counts the gap,
 * surfaced as a [DroppedMarker] before the next delivered event by [poll].
 */
class LogSubscriber(queueCap: Int) {
    private val queue = ArrayBlockingQueue<LogEvent>(queueCap)
    private val dropped = AtomicLong(0)

    internal fun offer(event: LogEvent) {
        while (!queue.offer(event)) {
            if (queue.poll() != null) dropped.incrementAndGet()
        }
    }

    /**
     * Deliver the next event, draining queued events FIRST so a sustained producer
     * burst can't starve real delivery. When the queue momentarily empties, a
     * pending drop count is surfaced as a [DroppedMarker]; otherwise blocks up to
     * [timeoutMs] for the next event.
     */
    fun poll(timeoutMs: Long): LogEvent? {
        queue.poll()?.let { return it }
        val d = dropped.getAndSet(0)
        if (d > 0) return DroppedMarker(d, System.currentTimeMillis())
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
