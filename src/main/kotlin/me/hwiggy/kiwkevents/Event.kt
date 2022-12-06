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
    interface Handler<TEvent : Event> : (TEvent) -> Unit {
        fun priority() = Priority.NORMAL
        fun ignoreCancelled() = false
    }

    /**
     * Responsible for dispatching events to the relevant handlers, as well as maintaining [Subscription]s
     */
    @Suppress("UNCHECKED_CAST") object Transport {
        private val participants = Multimaps.newSetMultimap<Participant, Closeable>(IdentityHashMap(), ::HashSet)
        private val listeners: Multimap<Class<*>, Handler<*>> = Multimaps.newSetMultimap(
            IdentityHashMap()
        ) { TreeSet(Comparator.comparingInt { it.priority().ordinal }) }

        /**
         * Submits an event to the [Transport], invoking the [Handler]s in order of their [Priority]
         */
        fun <TEvent : Event> submit(event: TEvent) {
            val type = event::class.java
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
            return AutoCloseable { abandon(participant) }
        }

        /**
         * A means of closing the [Participant] without them needing to track their [AutoCloseable]
         */
        fun abandon(participant: Participant) {
            participants.removeAll(participant).forEach(Closeable::close)
        }

        /**
         * Subscribes to events incoming to the [Transport] using a custom [Handler] implementation
         */
        fun <TEvent : Event> subscribe(type: Class<TEvent>, handler: Handler<TEvent>): Subscription {
            listeners.put(type, handler)
            return Closeable { listeners.remove(type, handler) }
        }

        /**
         * @see subscribe(Class, Handler)
         */
        inline fun <reified TEvent : Event> subscribe(handler: Handler<TEvent>) = subscribe(TEvent::class.java, handler)

        /**
         * Subscribes to events incoming to the [Transport] using an anonymous [Handler] implementation
         */
        fun <TEvent : Event> subscribe(
            type: Class<TEvent>,
            priority: Priority = Priority.NORMAL,
            ignoreCancelled: Boolean = false,
            block: (TEvent) -> Unit
        ): Subscription = subscribe(type, object : Handler<TEvent> {
            override fun priority() = priority
            override fun ignoreCancelled() = ignoreCancelled
            override fun invoke(p1: TEvent) = block(p1)
        })

        /**
         * @see subscribe(Class, Priority, Boolean, Consumer<TEvent>)
         */
        inline fun <reified TEvent : Event> subscribe(
            priority: Priority = Priority.NORMAL,
            ignoreCancelled: Boolean = false,
            noinline block: (TEvent) -> Unit
        ) = subscribe(TEvent::class.java, priority, ignoreCancelled, block)
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