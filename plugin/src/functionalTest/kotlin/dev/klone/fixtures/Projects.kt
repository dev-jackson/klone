package dev.klone.fixtures

/**
 * File maps for emulated Gradle projects. Libraries are plain `java-library` builds so the suite
 * runs without network access or an Android SDK; Klone's logic is the same for Android modules.
 */
object Projects {

    /** Single-module library exposing `<pkg>.<className>.version()` that returns [version]. */
    fun javaLibrary(
        name: String,
        version: String,
        group: String = "com.example",
        className: String = name.replaceFirstChar { it.uppercase() }.replace("-", ""),
        dependencies: String = "",
        groovy: Boolean = false
    ): Map<String, String> = buildMap {
        if (groovy) {
            put("settings.gradle", "rootProject.name = '$name'\n")
            put("build.gradle", "plugins { id 'java-library' }\ngroup = '$group'\ndependencies {\n$dependencies\n}\n")
        } else {
            put("settings.gradle.kts", "rootProject.name = \"$name\"\n")
            put("build.gradle.kts", "plugins { `java-library` }\ngroup = \"$group\"\ndependencies {\n$dependencies\n}\n")
        }
        putAll(javaClass(group, className, version))
    }

    /** Multi-module library: one `java-library` per module, plus a `:sample` app that must be ignored. */
    fun multiModuleLibrary(name: String, version: String, modules: List<String>, group: String = "com.example"): Map<String, String> = buildMap {
        put("settings.gradle.kts", buildString {
            appendLine("rootProject.name = \"$name\"")
            appendLine("include(")
            modules.forEach { appendLine("    \":$it\",") }
            appendLine(")")
            appendLine("include(\":sample\")")
        })
        put("gradle.properties", "group=$group\n")
        put("build.gradle.kts", "")
        modules.forEach { module ->
            put("$module/build.gradle.kts", "plugins { `java-library` }\n")
            javaClass(group, module.replaceFirstChar { it.uppercase() }, "$module-$version").forEach { (path, src) -> put("$module/$path", src) }
        }
        put("sample/build.gradle.kts", "plugins { application }\napplication { mainClass.set(\"Nope\") }\n")
    }

    fun javaClass(group: String, className: String, version: String): Map<String, String> {
        val pkg = group.replace('-', '_')
        return mapOf(
            "src/main/java/${pkg.replace('.', '/')}/$className.java" to """
                package $pkg;
                public class $className {
                    public static String version() { return "$version"; }
                }
            """.trimIndent()
        )
    }

    /** Host app: settings + `:app` (application plugin) whose Main prints each expression in [prints]. */
    fun host(
        dependencies: String,
        prints: List<String>,
        imports: List<String> = emptyList(),
        settingsExtra: String = "",
        catalog: String? = null
    ): Map<String, String> = buildMap {
        put("settings.gradle.kts", """
            plugins { id("io.github.dev-jackson.klone") }
            rootProject.name = "host"
            include(":app")
            $settingsExtra
        """.trimIndent() + "\n")
        put("app/build.gradle.kts", """
            plugins {
                application
                id("io.github.dev-jackson.klone.project")
            }
            application { mainClass.set("app.Main") }
            dependencies {
            $dependencies
            }
        """.trimIndent() + "\n")
        put("app/src/main/java/app/Main.java", buildString {
            appendLine("package app;")
            imports.forEach { appendLine("import $it;") }
            appendLine("public class Main {")
            appendLine("    public static void main(String[] args) {")
            prints.forEach { appendLine("        System.out.println(\"KLONE> \" + $it);") }
            appendLine("    }")
            appendLine("}")
        })
        if (catalog != null) put("gradle/libs.versions.toml", catalog.trimIndent() + "\n")
    }
}
