import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency
import org.tomlj.Toml
import org.tomlj.TomlParseResult

// APACHE-2 License
// Author: Tomáš Procházka <tomas.prochazka@gmail.com>

// This script will not modify existing files by default, it will create a copy of them and then you can decide what to use.
// Script support only dependencies defined in this format
// "com.squareup.okhttp3:okhttp:1.0.0"
// or with variable instead of version like:
// "com.squareup.okhttp3:okhttp:${rootProject.ext.okhttpLibVersion}"

enum class Operation {
    NOTHING, GENERATE_TOML, REPLACE_DEPS_BY_TOML
}

// What will script do when you run gradlew
// Together with GENERATE_TOML it can also replace variables with direct version and do simple to Kotlin conversion
val operation = Operation.NOTHING

// Name of file to scan in all modules, change to build.gradle.kts if you use Kotlin already
val inputFilesName = "build.gradle.kts"

// Input/Output location for TOML file
val toml = File(projectDir, "gradle/libs.versions.toml")

// Will replace custom variables like "com.squareup.okhttp3:okhttp:${rootProject.ext.okhttpLibVersion}" by used version directly
val replaceVariablesWithDirectVersion = true

// Replace original files
val replaceOriginalFiles = false

// Helper methods

/**
 * Convert string to camelCase with remove all non-word characters like _ or -
 */
fun String.toCamelCase(): String = split("""\W""".toRegex())
    .mapIndexed { index, s -> if (index > 0) s.capitalize() else s.decapitalize() }
    .joinToString("")

/**
 * Generate alias from dependency. Modify it to your requirements.
 */
fun generateAlias(d: Dependency): String {
    var alias = d.name.replace("-android", "")

    if (d.group!!.contains("androidx.test")) {
        alias = "androidx-test-$alias"
    } else if (d.group!!.contains("androidx")) {
        alias = "androidx-$alias"
    } else if (d.group == "com.google.ads.mediation") {
        alias = "googleAdsMediation-$alias"
    } else if (d.name == "lib") {
        alias = d.group!!.split(".").last()
    } else if (d.name == "af-android-sdk") {
        alias = "appsflyer"
    } else if (d.group!!.contains("okhttp") && !d.name.contains("okhttp")) {
        alias = "okhttp-" + d.name
    }

    return alias.toCamelCase()
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.tomlj:tomlj:1.0.0")
    }
}

println("TOML dependnecies conversion script init")

afterEvaluate {

    var deps: MutableSet<Dependency> = LinkedHashSet()
    var testDeps: MutableSet<Dependency> = LinkedHashSet()

    subprojects {
        afterEvaluate {
            configurations.toList().forEach { configuration ->
                val countD = configurations.named(configuration.name).get().allDependencies.size
                val countA = configurations.named(configuration.name).get().allArtifacts.size
                configurations.named(configuration.name).get().allDependencies.forEach {
                    if (configuration.name.startsWith("test") || configuration.name.startsWith("androidTest")) {
                        testDeps.add(it)
                    } else {
                        deps.add(it)
                    }
                }
            }
        }
    }

    var allGradleFiles = StringBuilder()
    projectDir.listFiles()?.forEach { folders ->
        folders.listFiles { _, name -> name == inputFilesName }?.forEach { file ->
            allGradleFiles.append(file.readText(Charsets.UTF_8))
        }
    }

    fun filter(input: Collection<Dependency>): Collection<Dependency> {
        // FIXME: It sort version just as String, so it can happen that older version will be used!!!
        return input.filterIsInstance<DefaultExternalModuleDependency>()
            .sortedByDescending { it.group + it.name + it.version }
            .distinctBy { it.group + it.name }
            .filter {
                // check if this dependency exists inside of gradle files
                allGradleFiles.contains("${it.group}:${it.name}")
            }
            .asReversed()
    }

    gradle.buildFinished {
        if (operation == Operation.NOTHING) {
            return@buildFinished
        }

        deps = LinkedHashSet(filter(deps))
        // also remove duplicities
        testDeps = LinkedHashSet(filter(testDeps).filter { !deps.contains(it) })

        if (operation == Operation.GENERATE_TOML) {
            println("Dependencies")
            deps.forEach {
                println("${it.group}:${it.name}:${it.version}")
            }

            println()
            println("Test Dependencies")
            testDeps.forEach {
                println("${it.group}:${it.name}:${it.version}")
            }

            fun convertFile(file: File) {
                println("Converting file: $file")
                var text = file.readText(Charsets.UTF_8)
                (deps + testDeps).forEach { dep ->
                    val depStringFinal = "${dep.group}:${dep.name}:${dep.version}"
                    val depString = "${dep.group}:${dep.name}:"
                    val re = Regex("""$depString[^"']+""")
                    text = re.replace(text, depStringFinal)
                }
                var fileNameSuffix = if (replaceOriginalFiles) "" else ".converted"
                File(file.parentFile, file.name + fileNameSuffix).writeText(text, Charsets.UTF_8)
            }

            // replace all dependencies with custom versioning with direct numbers
            if (replaceVariablesWithDirectVersion) {
                println("Do build script conversion to direct version definition...")
                projectDir.listFiles()?.forEach { folder ->
                    if (folder.isFile && folder.name == inputFilesName) {
                        convertFile(folder)
                    } else {
                        folder.listFiles { _, name -> name == inputFilesName }?.forEach { file -> convertFile(file) }
                    }
                }
            }

            // generate TOML file code
            println("Generate TOML file...")

            val versions = (deps + testDeps)
                .groupBy { it.group + "-" + it.version }
                .filter { it.value.size > 1 }

            val versionsAliases: MutableMap<Dependency, String> = HashMap()

            toml.writeText("")

            toml.appendText("[versions]\n")

            for (v in versions) {
                val d1 = v.value[0]
                val d2 = v.value[1]
                var versionAlias = generateAlias(d1).commonPrefixWith(generateAlias(d2))
                when (versionAlias) {
                    "firebaseCo" -> versionAlias = "firebase"
                    "kotlinStdlibJdk" -> versionAlias = "kotlinStdlib"
                    "androidxTestEspressoCo" -> versionAlias = "androidxTestEspresso"
                }
                if (d1.version != null) {
                    toml.appendText("""$versionAlias = "${d1.version}"""" + "\n")
                    v.value.forEach { d ->
                        // toml.appendText("     $d\n")
                        versionsAliases[d] = versionAlias
                    }
                }

            }

            toml.appendText("\n")
            toml.appendText("[libraries]\n")

            fun writeDependency(d: Dependency) {
                val alias = generateAlias(d)
                val isVersionRef = versionsAliases.containsKey(d)
                val version = if (isVersionRef) versionsAliases[d] else d.version
                if (version != null) {
                    val type = if (isVersionRef) ".ref" else ""
                    toml.appendText("""$alias = { module = "${d.group}:${d.name}", version$type="$version" }""")
                } else {
                    toml.appendText("""$alias = { module = "${d.group}:${d.name}"}""")
                }
                toml.appendText("\n")
            }

            deps.forEach { d ->
                writeDependency(d)
            }
            toml.appendText("\n")
            testDeps.forEach { d ->
                writeDependency(d)
            }
        }

        if (operation == Operation.REPLACE_DEPS_BY_TOML) {
            // read TOML file and replace direct dependencies
            val t: TomlParseResult = Toml.parse(toml.readText())
            println("Reading TOML file..." + t.errors())
            if (!t.hasErrors()) {
                val td = t.getTable("libraries")
                val libs = HashMap<String, String>()
                td!!.keySet().forEach { alias ->
                    val lib = td.getTableOrEmpty(alias).getString("module")?.also {
                        libs[it] = alias
                    }
                    println("$alias = $lib")
                }

                // replace all dependencies with aliases from TOML file
                if (replaceVariablesWithDirectVersion) {
                    val filenameSuffixForConvertedFile = if (replaceVariablesWithDirectVersion) "" else ".converted-toml"
                    projectDir.listFiles()?.forEach { folders ->
                        folders.listFiles { _, name -> name == inputFilesName }?.forEach { file ->
                            println("Converting file: $file")
                            var text = file.readText(Charsets.UTF_8)
                            libs.forEach { lib ->
                                val depStringFinal = "libs.${lib.value}"
                                val depString = lib.key
                                val re = Regex("""["']$depString:.*?["']+""")
                                text = re.replace(text, depStringFinal)
                            }
                            File(file.parentFile, file.name + filenameSuffixForConvertedFile).writeText(text, Charsets.UTF_8)
                        }
                    }
                }
            }
        }
    }
}
