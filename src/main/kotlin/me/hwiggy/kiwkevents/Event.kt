package me.hwiggy.kiwkevents

import com.google.common.collect.Multimap
import com.google.common.collect.Multimaps
import java.io.Closeable
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.Comparator
import kotlin.collections.HashSet

typealias Subscription = Closeable
interface Event {
    /**
     * Represents the order that events will arrive at the [Handler]
     */
    enum class Priority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }

    /**
     * Executor of all incoming event logic
     */
    fun interface Handler<TEvent> : (TEvent) -> Unit {
        fun priority() = Priority.NORMAL
        fun ignoreCancelled() = false
    }

    /**
     * Responsible for dispatching events to the relevant handlers, as well as maintaining [Subscription]s
     */
    @Suppress("UNCHECKED_CAST") abstract class Transport<TEvent : Any> {
        private val participants = Multimaps.newSetMultimap<Participant, Closeable>(IdentityHashMap(), ::HashSet)
        private val listeners: Multimap<Class<*>, Handler<*>> = Multimaps.newSetMultimap(
            IdentityHashMap()
        ) { TreeSet(Comparator.comparingInt { it.priority().ordinal }) }

        /**
         * Submits an event to the [Transport], invoking the [Handler]s in order of their [Priority]
         */
        fun submit(event: TEvent) {
            if (!shouldAccept(event)) return
            val type = digestType(event)
            val handlers = (listeners[type] ?: return) as Collection<Handler<TEvent>>
            handlers.forEach { handler ->
                if (event is Cancellable && event.cancelled && !handler.ignoreCancelled()) return@forEach
                handler.invoke(event)
            }
        }

        /**
         * Registers a [Participant] to receive [Event]s from this [Transport]
         * @return A [Closeable] reference to stop participating
         */
        fun participate(participant: Participant): AutoCloseable {
            if (participants.containsKey(participant)) throw IllegalArgumentException("A participant may only participate once!")
            val subscriptions = participant.subscribe()
            participants.putAll(participant, subscriptions)
            return AutoCloseable { withdraw(participant) }
        }

        /**
         * A means of closing the [Participant] without them needing to track their [AutoCloseable]
         */
        fun withdraw(participant: Participant) {
            participants.removeAll(participant).forEach(Closeable::close)
        }

        /**
         * Subscribes to events incoming to the [Transport] using a custom [Handler] implementation
         * @see [Participant] for a means of keeping track of returned [Subscription]s
         */
        fun subscribe(type: Class<*>, handler: Handler<out TEvent>): Subscription {
            listeners.put(type, handler)
            return Closeable { listeners.remove(type, handler) }
        }

        /**
         * @see subscribe(Class, Handler)
         * @see [Participant] for a means of keeping track of returned [Subscription]s
         */
        inline fun <reified REvent : TEvent> subscribe(handler: Handler<TEvent>) = subscribe(REvent::class.java, handler)

        /**
         * Subscribes to events incoming to the [Transport] using an anonymous [Handler] implementation
         * @see [Participant] for a means of keeping track of returned [Subscription]s
         */
        fun <REvent : TEvent> subscribe(
            type: Class<*>,
            priority: Priority = Priority.NORMAL,
            ignoreCancelled: Boolean = false,
            block: (REvent) -> Unit
        ): Subscription = subscribe(type, object : Handler<REvent> {
            override fun priority() = priority
            override fun ignoreCancelled() = ignoreCancelled
            override fun invoke(p1: REvent) = block(p1)
        })

        /**
         * @see subscribe(Class, Priority, Boolean, Consumer<TEvent>)
         * @see [Participant] for a means of keeping track of returned [Subscription]s
         */
        inline fun <reified REvent : TEvent> subscribe(
            priority: Priority = Priority.NORMAL,
            ignoreCancelled: Boolean = false,
            noinline block: (REvent) -> Unit
        ) = subscribe(REvent::class.java, priority, ignoreCancelled, block)

        /**
         * Whether this [Transport] should accept a specific [TEvent]
         */
        open fun shouldAccept(event: TEvent): Boolean = true

        /**
         * Digests the received [Event] into its key for finding [Handler]s
         */
        abstract fun digestType(event: TEvent): Class<*>
    }

    /**
     * The global [Transport], accepts all [Event]s indiscriminately
     */
    object GlobalTransport : Transport<Event>() {
        override fun digestType(event: Event) = event::class.java
    }

    /**
     * An object which provides [Subscription]s to specific [Event]s
     */
    interface Participant {
        fun subscribe(): Collection<Subscription>
    }
}

/**
 * Marker interface for [Event]s that may be cancelled
 */
interface Cancellable {
    var cancelled: Boolean
}