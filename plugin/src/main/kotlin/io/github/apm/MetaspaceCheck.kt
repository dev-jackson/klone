package dev.klone

import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import java.lang.management.ManagementFactory

/**
 * Every included build loads its own copy of its build plugins (AGP, Kotlin, Compose...), about 50 MB of
 * Metaspace per cloned repo. When `-XX:MaxMetaspaceSize` runs out the daemon loses the build thread and
 * the build hangs without an error, so Klone warns before that happens.
 */
internal object MetaspaceAdvisor {

    private const val MB = 1024L * 1024L
    // Measured on pokedex-compose: 154 MB with Maven dependencies, 628 MB with 9 cloned repos
    private const val BASE_MB = 160L
    private const val PER_REPO_MB = 60L

    /** Message when [usedBytes] is at least [warnPercent]% of [maxBytes]; null when fine or unlimited. */
    fun measured(usedBytes: Long, maxBytes: Long, warnPercent: Int): String? {
        if (maxBytes <= 0 || usedBytes * 100 < maxBytes * warnPercent) return null
        return "[Klone] Gradle daemon Metaspace at ${usedBytes / MB}/${maxBytes / MB} MB (${usedBytes * 100 / maxBytes}%). " +
            "Every cloned repo loads its own build plugins, and when Metaspace runs out the build hangs without an error. " +
            "Raise it in gradle.properties: org.gradle.jvmargs=... -XX:MaxMetaspaceSize=${recommendedMb(usedBytes / MB)}m"
    }

    /** Message when [repos] cloned repos are expected to need more than [maxBytes]; null when fine or unlimited. */
    fun estimated(repos: Int, maxBytes: Long): String? {
        if (maxBytes <= 0 || repos == 0) return null
        val neededMb = BASE_MB + PER_REPO_MB * repos
        if (neededMb * MB <= maxBytes) return null
        return "[Klone] $repos cloned repo(s) need about $neededMb MB of Metaspace, but the Gradle daemon is limited to " +
            "${maxBytes / MB} MB: the build may hang without an error. " +
            "Raise it in gradle.properties: org.gradle.jvmargs=... -XX:MaxMetaspaceSize=${recommendedMb(neededMb)}m"
    }

    /** 50% headroom, rounded up to 256 MB. */
    private fun recommendedMb(neededMb: Long): Long = ((neededMb * 3 / 2 + 255) / 256) * 256

    /** Used and max bytes of this JVM's Metaspace pool (max is -1 without -XX:MaxMetaspaceSize). */
    fun current(): Pair<Long, Long>? =
        ManagementFactory.getMemoryPoolMXBeans().firstOrNull { it.name == "Metaspace" }?.usage?.let { it.used to it.max }
}

/** Checks the real Metaspace usage when the build finishes (the service is closed then). */
abstract class MetaspaceCheck : BuildService<MetaspaceCheck.Params>, OperationCompletionListener, AutoCloseable {

    interface Params : BuildServiceParameters {
        val warnPercent: Property<Int>
    }

    override fun onFinish(event: FinishEvent) {}

    override fun close() {
        val (used, max) = MetaspaceAdvisor.current() ?: return
        MetaspaceAdvisor.measured(used, max, parameters.warnPercent.get())?.let {
            Logging.getLogger(MetaspaceCheck::class.java).warn(it)
        }
    }
}
