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
val inputFilesName = "build.gradle"

// Input/Output location for TOML file
val toml = File(projectDir, "gradle/libs.versions.toml")

// Will replace custom variables like "com.squareup.okhttp3:okhttp:${rootProject.ext.okhttpLibVersion}" by used version directly
val replaceVariablesWithDirectVersion = true

// It will never do a conversion on .kts files
val doToKotlinConversion = true

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

// Next code is based on https://github.com/bernaferrari/GradleKotlinConverter
// commit 24614a839defe5c494a3a9904abd40e3d8da0d94

// Bernardo Ferrari
// APACHE-2 License
val DEBUG = false

// anything with ' ('1.0.0', 'kotlin-android', 'jitpack', etc)
// becomes
// anything with " ("1.0.0", "kotlin-android", "jitpack", etc)
fun String.replaceApostrophes(): String = this.replace("'", "\"")

// def appcompat = "1.0.0"
// becomes
// val appcompat = "1.0.0"
fun String.replaceDefWithVal(): String = this.replace("(^|\\s)def ".toRegex()) { valReplacer ->
    // only convert when " def " or "def " (start of the line).
    // if a variable is named highdef, it won't be converted.
    valReplacer.value.replace("def", "val")
}

// final String<T> foo = "bar"
// becomes
// val foo: String<T> = "bar"
fun String.convertVariableDeclaration(): String {
    val varDeclExp = """(?:final\s+)?(\w+)(<.+>)? +(\w+)\s*=\s*(.+)""".toRegex()

    return this.replace(varDeclExp) {
        val (type, genericsType, id, value) = it.destructured
        if (type == "val") {
            "val $id = $value"
        } else {
            "val $id: $type${genericsType.orEmpty()} = $value"
        }
    }
}

// [items...]
// becomes
// listOf(items...)
fun String.convertArrayExpression(): String {
    val arrayExp = """\[([^\]]*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    return this.replace(arrayExp) {
        "listOf(${it.groupValues[1]})"
    }
}

// apply plugin: "kotlin-android"
// becomes
// apply(plugin = "kotlin-android")
fun String.convertPlugins(): String {
    val pluginsExp = """apply plugin: (\S+)""".toRegex()

    return this.replace(pluginsExp) {
        val (pluginId) = it.destructured
        // it identifies the plugin id and rebuilds the line.
        "apply(plugin = $pluginId)"
    }
}

// NEED TO RUN BEFORE [convertDependencies].
// compile ":epoxy-annotations"
// becomes
// implementation ":epoxy-annotations"
fun String.convertCompileToImplementation(): String {
    val outerExp = "(compile|testCompile)(?!O).*\".*\"".toRegex()

    return this.replace(outerExp) {

        if ("testCompile" in it.value) {
            it.value.replace("testCompile", "testImplementation")
        } else {
            it.value.replace("compile", "implementation")
        }
    }
}

// implementation ":epoxy-annotations"
// becomes
// implementation(":epoxy-annotations")
fun String.convertDependencies(): String {

    val testKeywords = "testImplementation|androidTestImplementation|debugImplementation|compileOnly|testCompileOnly|runtimeOnly|developmentOnly"
    val gradleKeywords = "($testKeywords|implementation|api|annotationProcessor|classpath|kapt|check)".toRegex()

    // ignore cases like kapt { correctErrorTypes = true } and apply plugin: ('kotlin-kapt") but pass kapt("...")
    // ignore keyWord followed by a space and a { or a " and a )
    val validKeywords = "(?!$gradleKeywords\\s*(\\{|\"\\)|\\.))$gradleKeywords.*".toRegex()

    return this.replace(validKeywords) { substring ->

        // retrieve the comment [//this is a comment], if any
        val comment = "\\s*\\/\\/.*".toRegex().find(substring.value)?.value ?: ""

        // remove the comment from the string. It will be added again at the end.
        val processedSubstring = substring.value.replace(comment, "")

        // we want to know if it is a implementation, api, etc
        val gradleKeyword = gradleKeywords.find(processedSubstring)?.value

        // implementation ':epoxy-annotations' becomes 'epoxy-annotations'
        val isolated = processedSubstring.replaceFirst(gradleKeywords, "").trim()

        // can't be && for the kapt project(':epoxy-processor') scenario, where there is a ) on the last element.
        if (isolated != "" && (isolated.first() != '(' || isolated.last { it != ' ' } != ')')) {
            "$gradleKeyword($isolated)$comment"
        } else {
            "$gradleKeyword$isolated$comment"
        }
    }
}

// signingConfig signingConfigs.release
// becomes
// signingConfig = signingConfigs.getByName("release")
fun String.convertSigningConfigBuildType(): String {
    val outerExp = "signingConfig.*signingConfigs.*".toRegex()

    return this.replace(outerExp) {
        // extracts release from signingConfig signingConfigs.release
        val release = it.value.replace("signingConfig.*signingConfigs.".toRegex(), "")
        "signingConfig = signingConfigs.getByName(\"$release\")"
    }
}

fun String.getExpressionBlock(
    expression: Regex,
    modifyResult: ((String) -> (String))
): String {

    val stringSize = this.count()

    return expression.findAll(this)
        .toList()
        .foldRight(this) { matchResult, accString ->

            var rangeStart = matchResult.range.last
            var rangeEnd = stringSize
            var count = 0

            if (DEBUG) {
                println("[DP] - range: ${matchResult.range} value: ${matchResult.value}")
            }

            for (item in rangeStart..stringSize) {
                if (this[item] == '{') count += 1 else if (this[item] == '}') count -= 1
                if (count == 0) {
                    rangeEnd = item
                    break
                }
            }

            if (DEBUG) {
                println("[DP] reading this block:\n${this.substring(rangeStart, rangeEnd)}")
            }

            val convertedStr = modifyResult.invoke(this.substring(rangeStart, rangeEnd))

            if (DEBUG) {
                println("[DP] outputing this block:\n$convertedStr")
            }

            accString.replaceRange(rangeStart, rangeEnd, convertedStr)
        }
}

fun String.convertNestedTypes(buildTypes: String, named: String): String {
    return this.getExpressionBlock("$buildTypes\\s*\\{".toRegex()) { substring ->
        substring.replace("\\S*\\s(?=\\{)".toRegex()) {
            val valueWithoutWhitespace = it.value.replace(" ", "")
            "$named(\"$valueWithoutWhitespace\")"
        }
    }
}

// buildTypes { release }
// becomes
// buildTypes { named("release") }
fun String.convertBuildTypes(): String = this.convertNestedTypes("buildTypes", "named")

// sourceSets { test }
// becomes
// sourceSets { named("test") }
fun String.convertSourceSets(): String = this.convertNestedTypes("sourceSets", "named")

// signingConfigs { release }
// becomes
// signingConfigs { register("release") }
fun String.convertSigningConfigs(): String = this.convertNestedTypes("signingConfigs", "register")

// maven { url "https://maven.fabric.io/public" }
// becomes
// maven("https://maven.fabric.io/public")
fun String.convertMaven(): String {

    val mavenExp = "maven\\s*\\{\\s*url\\s*(.*?)\\s*?}".toRegex()

    return this.replace(mavenExp) {
        it.value.replace("(= *uri *\\()|\\)|(url)|( )".toRegex(), "")
            .replace("{", "(")
            .replace("}", ")")
    }
}

// compileSdkVersion 28
// becomes
// compileSdkVersion(28)
fun String.addParentheses(): String {

    val sdkExp = "(compileSdkVersion|minSdkVersion|targetSdkVersion)\\s*\\d*".toRegex()

    return this.replace(sdkExp) {
        val split = it.value.split(" ")

        // if there is more than one whitespace, the last().toIntOrNull() will find.
        if (split.lastOrNull { it.toIntOrNull() != null } != null) {
            "${split[0]}(${split.last()})"
        } else {
            it.value
        }
    }
}

// id "io.gitlab.arturbosch.detekt" version "1.0.0.RC8"
// becomes
// id("io.gitlab.arturbosch.detekt") version "1.0.0.RC8"
fun String.addParenthesisToId(): String {

    // this will only catch id "..." version ..., should skip id("...")
    // should get the id "..."
    val idExp = "id\\s*\".*?\"".toRegex()

    return this.replace(idExp) {
        // remove the "id " before the real id
        val idValue = it.value.replace("id\\s*".toRegex(), "")
        "id($idValue)"
    }
}

// versionCode 4
// becomes
// versionCode = 4
fun String.addEquals(): String {

    val signing = "keyAlias|keyPassword|storeFile|storePassword"
    val other = "multiDexEnabled|correctErrorTypes"
    val defaultConfig = "applicationId|versionCode|versionName|testInstrumentationRunner"

    val versionExp = "($defaultConfig|$signing|$other).*".toRegex()

    return this.replace(versionExp) {
        val split = it.value.split(" ")

        // if there is more than one whitespace, the last().toIntOrNull() will find.
        if (split.lastOrNull { it.isNotBlank() } != null) {
            "${split[0]} = ${split.last()}"
        } else {
            it.value
        }
    }
}

// proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
// becomes
// setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
fun String.convertProguardFiles(): String {

    val proguardExp = "proguardFiles .*".toRegex()

    return this.replace(proguardExp) {
        val isolatedArgs = it.value.replace("proguardFiles\\s*".toRegex(), "")
        "setProguardFiles(listOf($isolatedArgs))"
    }
}

// ext.enableCrashlytics = false
// becomes
// extra.set("enableCrashlytics", false)
fun String.convertExtToExtra(): String {

    // get ext... but not ext { ... }
    val outerExp = """ext\.(\w+)\s*=\s*(.*)""".toRegex()

    return this.replace(outerExp) {
        val (name, value) = it.destructured

        "extra[\"$name\"] = $value"
    }
}

// sourceCompatibility = "1.8" or sourceCompatibility JavaVersion.VERSION_1_8
// becomes
// sourceCompatibility = JavaVersion.VERSION_1_8
fun String.convertJavaCompatibility(): String {

    val compatibilityExp = "(sourceCompatibility|targetCompatibility).*".toRegex()

    return this.replace(compatibilityExp) {
        val split = it.value.replace("\"]*".toRegex(), "").split(" ")

        if (split.lastOrNull() != null) {
            if ("JavaVersion" in split.last()) {
                "${split[0]} = ${split.last()}"
            } else {
                "${split[0]} = JavaVersion.VERSION_${split.last().replace(".", "_")}"
            }
        } else {
            it.value
        }
    }
}

// converts the clean task, which is very common to find
fun String.convertCleanTask(): String {

    val cleanExp = "task clean\\(type: Delete\\)\\s*\\{[\\s\\S]*}".toRegex()
    val registerClean = "tasks.register<Delete>(\"clean\").configure {\n" +
        "    delete(rootProject.buildDir)\n }"

    return this.replace(cleanExp, registerClean)
}

fun String.addIsToStr(blockTitle: String, transform: String): String {

    val extensionsExp = "$blockTitle\\s*\\{[\\s\\S]*\\}".toRegex()

    if (!extensionsExp.containsMatchIn(this)) return this

    val typesExp = "$transform.*".toRegex()

    return this.replace(typesExp) {

        val split = it.value.split(" ")

        if (DEBUG) {
            println("[AS] split:\n$split")
        }

        // if there is more than one whitespace, the last().toIntOrNull() will find.
        if (split.lastOrNull { it.isNotBlank() } != null) {
            "is${split[0].capitalize()} = ${split.last()}"
        } else {
            it.value
        }
    }
}

// androidExtensions { experimental = true }
// becomes
// androidExtensions { isExperimental = true }
fun String.convertInternalBlocks(): String {
    return this.addIsToStr("androidExtensions", "experimental")
        .addIsToStr("dataBinding", "enabled")
        .addIsToStr("lintOptions", "abortOnError")
        .addIsToStr("buildTypes", "debuggable")
        .addIsToStr("buildTypes", "minifyEnabled")
        .addIsToStr("buildTypes", "shrinkResources")
}

// include ":app", ":diffutils"
// becomes
// include(":app", ":diffutils")
fun String.convertInclude(): String {

    val expressionBase = "\\s*((\".*\"\\s*,)\\s*)*(\".*\")".toRegex()
    val includeExp = "include$expressionBase".toRegex()

    return this.replace(includeExp) { includeBlock ->
        // avoid cases where some lines at the start/end are blank
        val multiLine = includeBlock.value.split('\n').count { it.isNotBlank() } > 1

        val isolated = expressionBase.find(includeBlock.value)?.value ?: ""
        if (multiLine) "include(\n${isolated.trim()}\n)" else "include(${isolated.trim()})"
        // Possible visual improvement: when using multiline, the first line should have the same
        // margin/spacement as the others.
    }
}

// configurations.classpath.exclude group: 'com.android.tools.external.lombok'
// becomes
// configurations.classpath {
//    exclude(group = "com.android.tools.external.lombok")
// }
fun String.convertExcludeClasspath(): String {

    val fullLineExp = ".*configurations\\.classpath\\.exclude.*group:.*".toRegex()

    if (DEBUG) {
        println("[CEC] - reading this line: " + fullLineExp.find(this)?.value)
    }

    // this will extract "com.android.tools.external.lombok" from the string.
    val innerExp = "\\\".*\\\"".toRegex()

    return this.replace(fullLineExp) { isolatedLine ->
        val isolatedStr = innerExp.find(isolatedLine.value)?.value ?: ""
        "configurations.classpath {\n" +
            "    exclude(group = $isolatedStr)\n" +
            "}"
    }
}

// classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
// becomes
// classpath(kotlin("gradle-plugin", version = "$kotlin_version"))
//
// implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
// becomes
// implementation(kotlin("stdlib", KotlinCompilerVersion.VERSION))
fun String.convertJetBrainsKotlin(): String {

    // if string is implementation("..."), this will extract only the ...
    val fullLineExp = "\"org.jetbrains.kotlin:kotlin-.*(?=\\))".toRegex()

    val removeExp = "(?!org.jetbrains.kotlin:kotlin)-.*".toRegex()

    var shouldImportKotlinCompiler = false

    val newText = this.replace(fullLineExp) { isolatedLine ->

        // drop first "-" and remove last "
        val substring = (removeExp.find(isolatedLine.value)?.value ?: "").drop(1).replace("\"", "")

        val splittedSubstring = substring.split(":")

        if ("stdlib" in substring) {
            shouldImportKotlinCompiler = true
            "kotlin(\"stdlib\", KotlinCompilerVersion.VERSION)"
        } else if (splittedSubstring.size == 2) {
            "kotlin(\"${splittedSubstring[0]}\", version = \"${splittedSubstring[1]}\")"
        } else {
            "kotlin(\"${splittedSubstring[0]}\")"
        }
    }

    return if (shouldImportKotlinCompiler) {
        "import org.jetbrains.kotlin.config.KotlinCompilerVersion\n\n" + newText
    } else {
        newText
    }
}

// implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
// becomes
// implementation(kotlin("stdlib", KotlinCompilerVersion.VERSION))
fun String.convertPluginsIntoOneBlock(): String {

    // group plugin expressions. There can't be any space or tabs on the start of the line, else the regex will fail.
    // ok example:
    // apply(...)
    // apply(...)
    //
    // not ok example:
    // apply(...)
    //    apply(...)
    val fullLineExp = "(apply\\(plugin\\s*=\\s*\".*\"\\)[\\s\\S]){2,}".toRegex()

    val isolatedId = "\".*\"(?=\\))".toRegex()

    return this.replace(fullLineExp) { isolatedLine ->
        // this will fold the ids into a single string
        val plugins = isolatedId.findAll(isolatedLine.value)?.fold("") { acc, matchResult ->
            acc + "    id(${matchResult.value})\n"
        }
        "plugins {\n$plugins}\n"
    }
}

// testImplementation(group: "junit", name: "junit", version: "4.12")
// becomes
// testImplementation(group = "junit", name = "junit", version = "4.12")
fun String.replaceColonWithEquals(): String {

    // this get "group:"
    val expression = "\\w*:\\s*\".*?\"".toRegex()

    return this.replace(expression) {
        it.value.replace(":", " =")
    }
}

fun convertToKotlin(textToConvert: String): String {
    return textToConvert
        .replaceApostrophes()
        .replaceDefWithVal()
        .convertArrayExpression()
        .convertVariableDeclaration()
        .convertPlugins()
        .convertPluginsIntoOneBlock()
        .convertCompileToImplementation()
        .convertDependencies()
        .convertMaven()
        .addParentheses()
        .addEquals()
        .convertJavaCompatibility()
        .convertCleanTask()
        .convertProguardFiles()
        .convertInternalBlocks()
        .convertInclude()
        .convertBuildTypes()
        .convertSourceSets()
        .convertSigningConfigs()
        .convertExcludeClasspath()
        .convertJetBrainsKotlin()
        .convertSigningConfigBuildType()
        .convertExtToExtra()
        .addParenthesisToId()
        .replaceColonWithEquals()
}
// End of GradleKotlinConverter code

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
                if (doToKotlinConversion && file.extension != "kts") {
                    text = convertToKotlin(text)
                    fileNameSuffix = ".kts$fileNameSuffix"
                }
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
                toml.appendText("""$versionAlias = "${d1.version}"""" + "\n")

                v.value.forEach { d ->
                    // toml.appendText("     $d\n")
                    versionsAliases[d] = versionAlias
                }
            }

            toml.appendText("\n")
            toml.appendText("[libraries]\n")

            fun writeDependency(d: Dependency) {
                val alias = generateAlias(d)
                val isVersionRef = versionsAliases.containsKey(d)
                val version = if (isVersionRef) versionsAliases[d] else d.version
                val type = if (isVersionRef) ".ref" else ""
                toml.appendText("""$alias = { module = "${d.group}:${d.name}", version$type="$version" }""")
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
                val td = t.getTable("dependencies")
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
                            if (doToKotlinConversion && file.extension != "kts") {
                                text = convertToKotlin(text)
                            }
                            File(file.parentFile, file.name + filenameSuffixForConvertedFile).writeText(text, Charsets.UTF_8)
                        }
                    }
                }
            }
        }
    }
}