import groovy.lang.GroovySystem
import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.ArrayDeque
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.verification.DependencyVerificationMode
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.file.FileCollection
import org.gradle.api.flow.BuildWorkResult
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException

private object ScriptConfig {
    const val SCRIPT_FILE_NAME = "gradle-dependency-verification-artifacts.init.gradle.kts"
    const val RESOLVE_TASK_NAME = "resolveDependencyVerificationArtifacts"
    const val CHECK_TASK_NAME = "checkDependencyVerificationHashes"
    const val CLEAN_METADATA_PROPERTY_NAME = "cleanDependencyVerificationMetadata"
    const val METADATA_PATH = "gradle/verification-metadata.xml"
    const val GRADLE_DISTRIBUTIONS_REPOSITORY_NAME = "GrapheneOS Gradle distributions"
    const val DEPENDENCY_VERIFICATION_NAMESPACE =
        "https://schema.gradle.org/dependency-verification"
    const val KOTLIN_PLATFORM_TYPE_ATTRIBUTE_NAME = "org.jetbrains.kotlin.platform.type"
    const val GRADLE_METADATA_INDENT = "   "
}

private val kotlinPlatformTypeAttribute = Attribute.of(
    ScriptConfig.KOTLIN_PLATFORM_TYPE_ATTRIBUTE_NAME,
    String::class.java,
)

private typealias RollbackScheduler = (File, String, String) -> Unit

private object InitScriptServices {
    var buildFeatures: BuildFeatures? = null
    var schedule: RollbackScheduler? = null
}

abstract class RestoreDependencyVerificationMetadata :
    FlowAction<RestoreDependencyVerificationMetadata.Parameters> {
    private val logger = Logging.getLogger(RestoreDependencyVerificationMetadata::class.java)

    interface Parameters : FlowParameters {
        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val metadataFilePath: Property<String>

        @get:Input
        val metadataPath: Property<String>

        @get:Input
        val originalMetadata: Property<String>
    }

    override fun execute(parameters: Parameters) {
        if (parameters.buildResult.get().failure.isPresent) {
            File(parameters.metadataFilePath.get()).writeText(
                parameters.originalMetadata.get(),
                Charsets.UTF_8,
            )
            logger.lifecycle(
                "Restored ${parameters.metadataPath.get()} because dependency " +
                    "verification metadata generation failed.",
            )
        }
    }
}

abstract class DependencyVerificationMetadataRollbackPlugin @Inject constructor(
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
    private val buildFeatures: BuildFeatures,
) : Plugin<Gradle> {
    override fun apply(target: Gradle) {
        InitScriptServices.buildFeatures = buildFeatures
        val scheduler: RollbackScheduler = { metadataFile, metadataPath, originalMetadata ->
            flowScope.always(RestoreDependencyVerificationMetadata::class.java) {
                parameters.buildResult.set(flowProviders.buildWorkResult)
                parameters.metadataFilePath.set(metadataFile.absolutePath)
                parameters.metadataPath.set(metadataPath)
                parameters.originalMetadata.set(originalMetadata)
            }
        }
        InitScriptServices.schedule = scheduler
    }
}

apply<DependencyVerificationMetadataRollbackPlugin>()

private val componentsBlockRegex = Regex(
    pattern = """(?s)<components(?:\s[^>]*)?>.*?</components\s*>""",
)
private val selfClosingComponentsRegex = Regex(
    pattern = """<components\s*/>""",
)
private val rootCloseRegex = Regex(
    pattern = """</verification-metadata\s*>""",
)
private val taskNameWordRegex = Regex("""[A-Z]?[a-z0-9]+|[A-Z]+(?=[A-Z]|$)""")

private fun taskNameWords(taskName: String): List<String> {
    return taskNameWordRegex.findAll(taskName)
        .map { it.value }
        .toList()
}

/**
 * Matches Gradle's common task selector forms for our dedicated tasks: exact
 * names, qualified names, literal prefixes past the first word, and camel-case
 * abbreviations. A selector must cover more than the first word so generic
 * tasks such as `check` and `resolve` do not activate this init script's gates.
 */
private fun taskSelectorMatches(requestedTask: String, taskName: String): Boolean {
    val candidate = requestedTask.substringAfterLast(":")
    if (candidate == taskName) {
        return true
    }
    if (candidate.length < 2) {
        return false
    }
    val taskNameWords = taskNameWords(taskName)
    val firstWordLength = taskNameWords.firstOrNull()?.length ?: taskName.length
    if (candidate.length > firstWordLength && taskName.startsWith(candidate)) {
        return true
    }

    var candidateIndex = 0
    var matchedWordCount = 0
    taskNameWords.forEach { word ->
        if (candidateIndex == candidate.length) {
            return@forEach
        }

        var matchedCharacters = 0
        while (
            candidateIndex < candidate.length &&
            matchedCharacters < word.length &&
            candidate[candidateIndex].equals(word[matchedCharacters], ignoreCase = true)
        ) {
            candidateIndex += 1
            matchedCharacters += 1
        }

        if (matchedCharacters == 0) {
            return false
        }
        matchedWordCount += 1
    }

    return candidateIndex == candidate.length && matchedWordCount > 1
}

private fun requestedTask(taskName: String): Boolean {
    return gradle.startParameter.taskNames.any { requestedTask ->
        taskSelectorMatches(requestedTask, taskName)
    }
}

private fun propertyEnabled(propertyName: String): Boolean {
    return gradle.startParameter.projectProperties[propertyName]
        ?.lowercase()
        ?.let { it == "true" || it == "1" || it == "yes" }
        ?: false
}

private val buildFeatures = InitScriptServices.buildFeatures
    ?: throw GradleException("Dependency verification init script services were not initialized")
private val configurationCacheRequested =
    buildFeatures.configurationCache.requested.getOrElse(false)
private val checkTaskRequested = requestedTask(ScriptConfig.CHECK_TASK_NAME)
private val resolveTaskRequested = requestedTask(ScriptConfig.RESOLVE_TASK_NAME)
private val writeVerificationMetadataRequested =
    gradle.startParameter.writeDependencyVerifications.isNotEmpty()
private val cleanMetadataPropertyRequested = propertyEnabled(
    ScriptConfig.CLEAN_METADATA_PROPERTY_NAME,
)

if (checkTaskRequested) {
    gradle.startParameter.setDependencyVerificationMode(DependencyVerificationMode.STRICT)
}

if (
    (checkTaskRequested || resolveTaskRequested) &&
    configurationCacheRequested
) {
    throw GradleException(
        "${ScriptConfig.RESOLVE_TASK_NAME} and ${ScriptConfig.CHECK_TASK_NAME} " +
            "are not compatible with Gradle's " +
            "configuration cache. " +
            "Run the command again with --no-configuration-cache.",
    )
}

if (resolveTaskRequested && writeVerificationMetadataRequested && gradle.startParameter.isDryRun) {
    throw GradleException(
        "${ScriptConfig.RESOLVE_TASK_NAME} cannot be used with " +
            "--write-verification-metadata " +
            "and --dry-run because metadata " +
            "cleanup requires the resolver task to execute. Run without --dry-run.",
    )
}

if (cleanMetadataPropertyRequested) {
    throw GradleException(
        "${ScriptConfig.CLEAN_METADATA_PROPERTY_NAME} is not supported as a " +
            "standalone option. Run ${ScriptConfig.RESOLVE_TASK_NAME} with " +
            "--write-verification-metadata instead.",
    )
}

private fun DocumentBuilderFactory.setXmlFeature(feature: String, value: Boolean) {
    try {
        setFeature(feature, value)
    } catch (exception: ParserConfigurationException) {
        throw GradleException(
            "XML parser does not support required feature $feature=$value",
            exception,
        )
    }
}

private fun DocumentBuilderFactory.setXmlAttribute(name: String, value: String) {
    try {
        setAttribute(name, value)
    } catch (exception: IllegalArgumentException) {
        throw GradleException("XML parser does not support required attribute $name", exception)
    }
}

private fun verificationMetadataDocumentBuilderFactory(): DocumentBuilderFactory {
    return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setXmlFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setXmlFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setXmlFeature("http://xml.org/sax/features/external-general-entities", false)
        setXmlFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setXmlFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setXmlAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setXmlAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}

private fun Element.directChildElements(localName: String): List<Element> {
    return (0 until childNodes.length).mapNotNull { index ->
        val child = childNodes.item(index)
        if (child.nodeType != Node.ELEMENT_NODE) {
            return@mapNotNull null
        }

        val element = child as Element
        if (
            element.localName == localName &&
            element.namespaceURI == ScriptConfig.DEPENDENCY_VERIFICATION_NAMESPACE
        ) {
            element
        } else {
            null
        }
    }
}

private fun parseComponentsBlockCount(
    metadataFile: File,
    contents: String,
): Int {
    try {
        val document = verificationMetadataDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(contents)))
        val root = document.documentElement

        if (
            root.localName != "verification-metadata" ||
            root.namespaceURI != ScriptConfig.DEPENDENCY_VERIFICATION_NAMESPACE
        ) {
            throw GradleException(
                "Unexpected dependency verification metadata root in ${metadataFile.absolutePath}",
            )
        }

        return root.directChildElements("components").size
    } catch (exception: GradleException) {
        throw exception
    } catch (exception: ParserConfigurationException) {
        throw GradleException(
            "Could not configure XML parser for ${metadataFile.absolutePath}",
            exception,
        )
    } catch (exception: SAXException) {
        throw GradleException("Could not parse ${metadataFile.absolutePath}", exception)
    } catch (exception: IOException) {
        throw GradleException("Could not read ${metadataFile.absolutePath}", exception)
    }
}

private data class PinnedComponent(
    val coordinate: ModuleCoordinate,
    val artifactNames: List<String>,
)

private fun parseVerificationMetadataPinnedComponents(metadataFile: File): List<PinnedComponent> {
    if (!metadataFile.isFile) {
        return emptyList()
    }

    val document = try {
        verificationMetadataDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(metadataFile)
    } catch (exception: ParserConfigurationException) {
        throw GradleException(
            "Could not configure XML parser for ${metadataFile.absolutePath}",
            exception,
        )
    } catch (exception: SAXException) {
        throw GradleException("Could not parse ${metadataFile.absolutePath}", exception)
    } catch (exception: IOException) {
        throw GradleException("Could not read ${metadataFile.absolutePath}", exception)
    }

    val root = document.documentElement
    if (
        root.localName != "verification-metadata" ||
        root.namespaceURI != ScriptConfig.DEPENDENCY_VERIFICATION_NAMESPACE
    ) {
        throw GradleException(
            "Unexpected dependency verification metadata root in ${metadataFile.absolutePath}",
        )
    }

    return root.directChildElements("components")
        .flatMap { components -> components.directChildElements("component") }
        .mapNotNull { component ->
            val group = component.getAttribute("group").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val module = component.getAttribute("name").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val version = component.getAttribute("version").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val artifactNames = component.directChildElements("artifact")
                .mapNotNull { artifact ->
                    artifact.getAttribute("name").takeIf { it.isNotEmpty() }
                }
            PinnedComponent(
                coordinate = ModuleCoordinate(
                    group = group,
                    module = module,
                    version = version,
                ),
                artifactNames = artifactNames,
            )
        }
}

private fun parseVerificationMetadataComponents(metadataFile: File): Set<ModuleCoordinate> {
    return parseVerificationMetadataPinnedComponents(metadataFile)
        .map { component -> component.coordinate }
        .toSet()
}

private fun lineSeparator(contents: String): String {
    return if (contents.contains("\r\n")) "\r\n" else "\n"
}

private fun indentationAt(contents: String, index: Int): String {
    val lineStart = contents.lastIndexOf('\n', startIndex = index - 1)
        .let { if (it == -1) 0 else it + 1 }
    val linePrefix = contents.substring(lineStart, index)
    return if (linePrefix.all { it == ' ' || it == '\t' }) linePrefix else ""
}

private fun replaceComponentsBlock(metadataFile: File, contents: String, newline: String): String {
    val componentsMatches = componentsBlockRegex.findAll(contents).toList()
    val selfClosingComponentsMatches = selfClosingComponentsRegex.findAll(contents).toList()
    val matches = (componentsMatches + selfClosingComponentsMatches)
        .sortedBy { it.range.first }

    if (matches.size != 1) {
        throw GradleException(
            "Could not safely locate the single <components> block in ${metadataFile.absolutePath}",
        )
    }

    val match = matches.single()
    val indent = indentationAt(contents, match.range.first)
    val replacement = "$indent<components>$newline$indent</components>"
    return contents.replaceRange(match.range, replacement)
}

private fun insertComponentsBlock(metadataFile: File, contents: String, newline: String): String {
    val rootCloseMatches = rootCloseRegex.findAll(contents).toList()
    if (rootCloseMatches.size != 1) {
        throw GradleException(
            "Could not safely locate </verification-metadata> in ${metadataFile.absolutePath}",
        )
    }

    val rootCloseMatch = rootCloseMatches.single()
    val rootCloseIndent = indentationAt(contents, rootCloseMatch.range.first)
    val componentsIndent = "$rootCloseIndent${ScriptConfig.GRADLE_METADATA_INDENT}"
    val componentsBlock =
        "$componentsIndent<components>$newline$componentsIndent</components>$newline"
    return contents.substring(0, rootCloseMatch.range.first) +
        componentsBlock +
        contents.substring(rootCloseMatch.range.first)
}

private fun emptyComponentsBlock(metadataFile: File): String {
    if (!metadataFile.isFile) {
        throw GradleException(
            "Gradle dependency verification metadata not found: ${metadataFile.absolutePath}",
        )
    }

    val original = metadataFile.readText(Charsets.UTF_8)
    val originalComponentsBlockCount = parseComponentsBlockCount(metadataFile, original)
    val newline = lineSeparator(original)
    val updated = when (originalComponentsBlockCount) {
        0 -> insertComponentsBlock(metadataFile, original, newline)
        1 -> replaceComponentsBlock(metadataFile, original, newline)
        else -> throw GradleException(
            "Expected at most one <components> block in ${metadataFile.absolutePath}, " +
                "found $originalComponentsBlockCount",
        )
    }

    val updatedComponentsBlockCount = parseComponentsBlockCount(metadataFile, updated)
    if (updatedComponentsBlockCount != 1) {
        throw GradleException(
            "Expected exactly one <components> block in ${metadataFile.absolutePath} " +
                "after rewrite",
        )
    }

    metadataFile.writeText(updated, Charsets.UTF_8)
    return original
}

private val cleanMetadataRequested = resolveTaskRequested && writeVerificationMetadataRequested

gradle.beforeSettings {
    if (cleanMetadataRequested) {
        val metadataFile = settingsDir.resolve(ScriptConfig.METADATA_PATH)
        val originalMetadata = emptyComponentsBlock(metadataFile)
        val scheduleRollback = InitScriptServices.schedule
            ?: throw GradleException(
                "Dependency verification metadata rollback plugin was not initialized",
            )
        scheduleRollback(metadataFile, ScriptConfig.METADATA_PATH, originalMetadata)
        logger.lifecycle(
            "Emptied ${ScriptConfig.METADATA_PATH} before regenerating " +
                "dependency verification hashes.",
        )
    }
}

private var repositoriesMode = RepositoriesMode.PREFER_PROJECT

private fun RepositoryHandler.addGradleDistributionsRepository(
    repositoryName: String,
): ArtifactRepository {
    return ivy {
        name = repositoryName
        url = URI.create("https://services.gradle.org/distributions")
        patternLayout {
            artifact("[module]-[revision]-[classifier].[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeModule("gradle", "gradle")
        }
    }
}

private fun RepositoryHandler.ensureGradleDistributionsRepository() {
    if (findByName(ScriptConfig.GRADLE_DISTRIBUTIONS_REPOSITORY_NAME) == null) {
        addGradleDistributionsRepository(
            ScriptConfig.GRADLE_DISTRIBUTIONS_REPOSITORY_NAME,
        )
    }
}

settingsEvaluated {
    repositoriesMode = dependencyResolutionManagement.repositoriesMode.get()
    dependencyResolutionManagement {
        repositories {
            ensureGradleDistributionsRepository()
        }
    }
}

private class ResolveStats {
    var primaryConfigurationCount: Int = 0
    var externalModuleFallbackConfigurationCount: Int = 0
    var dependencyMetadataArtifactCount: Int = 0
    var sourceClassifierArtifactCount: Int = 0
    var javadocClassifierArtifactCount: Int = 0
    var metadataDocumentationArtifactCount: Int = 0
    var gradleEmbeddedLibraryArtifactCount: Int = 0
    var hostToolArtifactCount: Int = 0
    var gradleSourceDistributionCount: Int = 0
    var pinnedVerificationArtifactCount: Int = 0
}

private data class ModuleId(
    val group: String,
    val module: String,
) {
    fun matches(coordinate: ModuleCoordinate): Boolean {
        return coordinate.group == group && coordinate.module == module
    }
}

private data class ModuleCoordinate(
    val group: String,
    val module: String,
    val version: String,
) {
    fun notation(suffix: String = ""): String {
        return "$group:$module:$version$suffix"
    }
}

private fun String.isExactModuleVersion(): Boolean {
    return isNotEmpty() &&
        !contains("+") &&
        !contains("[") &&
        !contains("]") &&
        !contains("(") &&
        !contains(")") &&
        !contains(",")
}

private fun ModuleComponentSelector.exactModuleCoordinate(): ModuleCoordinate? {
    val version = listOf(
        versionConstraint.requiredVersion,
        versionConstraint.strictVersion,
        versionConstraint.preferredVersion,
    ).firstOrNull { it.isNotEmpty() } ?: return null
    if (!version.isExactModuleVersion()) {
        return null
    }

    return ModuleCoordinate(
        group = group,
        module = module,
        version = version,
    )
}

private data class HostToolRule(
    val marker: ModuleId,
    val artifact: ModuleId,
    val classifiers: List<String?>,
    val extension: String,
    val transitive: Boolean = false,
)

private data class HostToolRequest(
    val rule: HostToolRule,
    val coordinate: ModuleCoordinate,
)

private fun HostToolRule.classifierSortKey(): String {
    return classifiers.joinToString(",") { classifier -> classifier ?: "" }
}

private data class ModuleRequest(
    val coordinate: ModuleCoordinate,
    val jvmEnvironments: Set<String>,
)

private data class ModuleMetadataDocumentationArtifact(
    val classifier: String,
    val docsType: String,
)

private data class ParsedGradleModuleMetadata(
    val dependencies: Set<ModuleCoordinate>,
    val documentationArtifacts: Set<ModuleMetadataDocumentationArtifact>,
)

private enum class ConfigurationOwner(
    val label: String,
) {
    BUILDSCRIPT("buildscript"),
    PROJECT("project"),
}

private data class ModuleRequestScope(
    val projectPath: String,
    val owner: ConfigurationOwner,
)

private data class ScopedModuleRequests(
    val project: Project,
    val owner: ConfigurationOwner,
    val requests: MutableMap<ModuleCoordinate, ModuleRequest>,
)

private fun ScopedModuleRequests.displayPrefix(): String {
    return "${project.path}:${owner.label}"
}

private data class ResolvedConfigurationArtifacts(
    val moduleRequests: Map<ModuleCoordinate, ModuleRequest>,
    val usedExternalModuleFallback: Boolean,
)

private data class ScopedModuleCoordinate(
    val project: Project,
    val owner: ConfigurationOwner,
    val coordinate: ModuleCoordinate,
)

private data class ScopedModuleCoordinateKey(
    val projectPath: String,
    val owner: ConfigurationOwner,
    val coordinate: ModuleCoordinate,
)

private data class ScopedDocumentationArtifactKey(
    val projectPath: String,
    val owner: ConfigurationOwner,
    val coordinate: ModuleCoordinate,
    val classifier: String,
)

private data class ResolvedDetachedArtifacts(
    val files: Set<File>,
    val moduleRequests: Map<ModuleCoordinate, ModuleRequest>,
)

private val hostToolRules = listOf(
    HostToolRule(
        marker = ModuleId("com.android.tools.build", "aapt2-proto"),
        artifact = ModuleId("com.android.tools.build", "aapt2"),
        classifiers = listOf("linux", "osx", "windows"),
        extension = "jar",
    ),
    HostToolRule(
        marker = ModuleId("com.google.devtools.ksp", "symbol-processing-gradle-plugin"),
        artifact = ModuleId("com.google.devtools.ksp", "symbol-processing-aa-embeddable"),
        classifiers = listOf(null),
        extension = "jar",
        transitive = true,
    ),
)

private fun Iterable<Configuration>.resolvableConfigurations(): List<Configuration> {
    return filter { it.isCanBeResolved }
        .sortedBy { it.name }
}

private fun Configuration.displayName(project: Project, owner: ConfigurationOwner): String {
    return "${project.path}:${owner.label}:$name"
}

private fun Throwable.messageChain(): List<String> {
    return failureChain()
        .mapNotNull { it.message }
        .toList()
}

private fun Throwable.failureChain(): Sequence<Throwable> {
    return sequence {
        val pending = ArrayDeque<Throwable>()
        val seen = mutableSetOf<Throwable>()
        pending.add(this@failureChain)

        while (!pending.isEmpty()) {
            val failure = pending.removeFirst()
            if (!seen.add(failure)) {
                continue
            }

            yield(failure)
            failure.cause?.let { pending.add(it) }
            failure.suppressed.forEach { pending.add(it) }
        }
    }
}

private val dependencyVerificationExceptionClassNames = setOf(
    "org.gradle.api.internal.artifacts.verification.exceptions.ComponentVerificationException",
    "org.gradle.api.internal.artifacts.verification.exceptions.DependencyVerificationException",
)

private val dependencyVerificationExceptionSimpleNames = setOf(
    "ComponentVerificationException",
    "DependencyVerificationException",
)

private val missingOptionalArtifactExceptionSimpleNames = setOf(
    "ArtifactNotFoundException",
    "ModuleVersionNotFoundException",
)

private fun Throwable.isDependencyVerificationFailure(): Boolean {
    return failureChain().any { failure ->
        val className = failure.javaClass.name
        val simpleName = failure.javaClass.simpleName
        className in dependencyVerificationExceptionClassNames ||
            simpleName in dependencyVerificationExceptionSimpleNames ||
            failure.message?.contains("Dependency verification failed") == true
    }
}

private fun Throwable.isMissingOptionalArtifactFailure(): Boolean {
    return failureChain().any { failure ->
        failure.javaClass.simpleName in missingOptionalArtifactExceptionSimpleNames ||
            failure.message?.contains("Could not find ") == true
    }
}

private fun Throwable.isStandaloneResolutionTimingFailure(): Boolean {
    return messageChain().any { message ->
        message.contains("Failed to query the value of task") ||
            (
                message.contains("Querying the mapped value of provider") &&
                    message.contains("before task") &&
                    message.contains("has completed is not supported")
            ) ||
            (
                message.contains("Cannot query the value of task") &&
                    message.contains("has not completed")
            )
    }
}

private fun <T : Any> copyAttribute(
    attribute: Attribute<T>,
    source: AttributeContainer,
    target: AttributeContainer,
) {
    val value = source.getAttribute(attribute) ?: return
    target.attribute(attribute, value)
}

private fun copyAttributes(source: Configuration, target: Configuration) {
    source.attributes.keySet().forEach { attribute ->
        copyAttribute(attribute, source.attributes, target.attributes)
    }
}

private fun externalModuleConfigurationCopy(configuration: Configuration): Configuration {
    val copy = configuration.copyRecursive(
        Spec<Dependency> { dependency ->
            dependency is ExternalModuleDependency
        },
    )
    copy.isTransitive = configuration.isTransitive
    copyAttributes(configuration, copy)
    return copy
}

private fun FileCollection.forceResolveFiles() {
    if (files.isEmpty()) {
        return
    }
}

private fun FileCollection.resolvedFiles(): Set<File> {
    return files
}

private fun Element.childElements(localName: String): List<Element> {
    return (0 until childNodes.length)
        .map { index -> childNodes.item(index) }
        .filterIsInstance<Element>()
        .filter { child -> child.localName == localName || child.nodeName == localName }
}

private fun Element.childText(localName: String): String? {
    return childElements(localName)
        .firstOrNull()
        ?.textContent
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun String.resolvePomProperties(properties: Map<String, String>): String? {
    var resolved = trim()
    repeat(10) {
        val matches = Regex("""\$\{([^}]+)}""").findAll(resolved).toList()
        if (matches.isEmpty()) {
            return resolved.takeIf { it.isNotEmpty() }
        }

        matches.forEach { match ->
            val propertyName = match.groupValues[1]
            val propertyValue = properties[propertyName] ?: return null
            resolved = resolved.replace(match.value, propertyValue)
        }
    }

    return null
}

private fun Element.pomProperties(currentModule: ModuleCoordinate): Map<String, String> {
    val parent = childElements("parent").firstOrNull()
    val group = childText("groupId") ?: parent?.childText("groupId") ?: currentModule.group
    val version = childText("version") ?: parent?.childText("version") ?: currentModule.version
    val artifact = childText("artifactId") ?: currentModule.module

    val properties = mutableMapOf(
        "groupId" to group,
        "artifactId" to artifact,
        "version" to version,
        "project.groupId" to group,
        "project.artifactId" to artifact,
        "project.version" to version,
        "pom.groupId" to group,
        "pom.artifactId" to artifact,
        "pom.version" to version,
    )

    childElements("properties").firstOrNull()?.let { propertiesElement ->
        (0 until propertiesElement.childNodes.length)
            .map { index -> propertiesElement.childNodes.item(index) }
            .filterIsInstance<Element>()
            .forEach { property ->
                val propertyName = property.localName ?: property.nodeName
                val propertyValue = property.textContent.trim()
                if (propertyValue.isNotEmpty()) {
                    properties[propertyName] = propertyValue
                }
            }
    }

    return properties
}

private fun Element.moduleCoordinate(
    properties: Map<String, String>,
): ModuleCoordinate? {
    val group = childText("groupId")?.resolvePomProperties(properties) ?: return null
    val module = childText("artifactId")?.resolvePomProperties(properties) ?: return null
    val version = childText("version")?.resolvePomProperties(properties) ?: return null
    return ModuleCoordinate(group = group, module = module, version = version)
}

private fun parseMavenPomMetadata(
    pomFile: File,
    currentModule: ModuleCoordinate,
): Set<ModuleCoordinate> {
    val document = try {
        verificationMetadataDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(pomFile)
    } catch (exception: ParserConfigurationException) {
        throw GradleException(
            "Could not configure XML parser for ${pomFile.absolutePath}",
            exception,
        )
    } catch (exception: SAXException) {
        throw GradleException("Could not parse ${pomFile.absolutePath}", exception)
    } catch (exception: IOException) {
        throw GradleException("Could not read ${pomFile.absolutePath}", exception)
    }

    val root = document.documentElement
    if (root.localName != "project" && root.nodeName != "project") {
        return emptySet()
    }

    val properties = root.pomProperties(currentModule)
    val discoveredModules = mutableSetOf<ModuleCoordinate>()

    root.childElements("parent")
        .firstOrNull()
        ?.moduleCoordinate(properties)
        ?.let { parent -> discoveredModules.add(parent) }

    root.childElements("dependencyManagement")
        .flatMap { dependencyManagement -> dependencyManagement.childElements("dependencies") }
        .flatMap { dependencies -> dependencies.childElements("dependency") }
        .filter { dependency ->
            val type = dependency.childText("type")
                ?.resolvePomProperties(properties)
                ?: "jar"
            val scope = dependency.childText("scope")
                ?.resolvePomProperties(properties)
            type == "pom" && scope == "import"
        }
        .mapNotNull { dependency -> dependency.moduleCoordinate(properties) }
        .forEach { importedBom -> discoveredModules.add(importedBom) }

    return discoveredModules
}

private fun moduleMetadataVersion(version: Any?): String? {
    return when (version) {
        is String -> version
        is Map<*, *> -> {
            listOf("requires", "strictly", "prefers")
                .firstNotNullOfOrNull { key -> version[key] as? String }
        }
        else -> null
    }
}

private fun moduleMetadataDependencyCoordinate(
    dependency: Any?,
): ModuleCoordinate? {
    val dependencyMap = dependency as? Map<*, *> ?: return null
    val group = dependencyMap["group"] as? String ?: return null
    val module = dependencyMap["module"] as? String ?: return null
    val version = moduleMetadataVersion(dependencyMap["version"]) ?: return null
    return ModuleCoordinate(group = group, module = module, version = version)
}

private fun moduleMetadataDocumentationArtifact(
    currentModule: ModuleCoordinate,
    variant: Map<*, *>,
    file: Any?,
): ModuleMetadataDocumentationArtifact? {
    val attributes = variant["attributes"] as? Map<*, *> ?: return null
    if (attributes["org.gradle.category"] != Category.DOCUMENTATION) {
        return null
    }

    val docsType = attributes["org.gradle.docstype"] as? String ?: return null
    if (docsType != DocsType.SOURCES && docsType != DocsType.JAVADOC) {
        return null
    }

    val fileMap = file as? Map<*, *> ?: return null
    val name = (fileMap["name"] as? String) ?: (fileMap["url"] as? String) ?: return null
    if (!name.endsWith(".jar")) {
        return null
    }

    val prefix = "${currentModule.module}-${currentModule.version}-"
    if (!name.startsWith(prefix)) {
        return null
    }

    val classifier = name
        .removePrefix(prefix)
        .removeSuffix(".jar")
        .takeIf { it.isNotEmpty() }
        ?: return null
    return ModuleMetadataDocumentationArtifact(
        classifier = classifier,
        docsType = docsType,
    )
}

private fun parseGradleModuleMetadata(
    moduleMetadataFile: File,
    currentModule: ModuleCoordinate,
): ParsedGradleModuleMetadata {
    val metadata = try {
        JsonSlurper().parse(moduleMetadataFile)
    } catch (exception: Exception) {
        throw GradleException(
            "Could not parse ${moduleMetadataFile.absolutePath}",
            exception,
        )
    }

    val root = metadata as? Map<*, *> ?: return ParsedGradleModuleMetadata(
        dependencies = emptySet(),
        documentationArtifacts = emptySet(),
    )
    val variants = root["variants"] as? Iterable<*> ?: return ParsedGradleModuleMetadata(
        dependencies = emptySet(),
        documentationArtifacts = emptySet(),
    )

    val dependencies = variants
        .flatMap { variant ->
            val variantMap = variant as? Map<*, *> ?: return@flatMap emptyList()
            val dependencies = variantMap["dependencies"] as? Iterable<*> ?: emptyList<Any>()
            dependencies.mapNotNull(::moduleMetadataDependencyCoordinate)
        }
        .toSet()

    val documentationArtifacts = variants
        .flatMap { variant ->
            val variantMap = variant as? Map<*, *> ?: return@flatMap emptyList()
            val files = variantMap["files"] as? Iterable<*> ?: emptyList<Any>()
            files.mapNotNull { file ->
                moduleMetadataDocumentationArtifact(
                    currentModule = currentModule,
                    variant = variantMap,
                    file = file,
                )
            }
        }
        .toSet()

    return ParsedGradleModuleMetadata(
        dependencies = dependencies,
        documentationArtifacts = documentationArtifacts,
    )
}

private fun resolvePrimaryArtifacts(configuration: Configuration) {
    /*
     * Resolve external module artifacts only. Project outputs are not stored in
     * dependency verification metadata, and Android app projects expose internal
     * variants that can be ambiguous when a configuration is resolved
     * generically.
     */
    val artifacts = configuration.incoming.artifactView {
        componentFilter { componentId ->
            componentId is ModuleComponentIdentifier
        }
    }.artifacts

    artifacts.artifactFiles.forceResolveFiles()
}

private fun resolvedModuleRequests(
    configuration: Configuration,
): Map<ModuleCoordinate, ModuleRequest> {
    val requests = mutableMapOf<ModuleCoordinate, ModuleRequest>()
    configuration.incoming.resolutionResult.allComponents
        .forEach { component ->
            val module = component.id as? ModuleComponentIdentifier ?: return@forEach
            val coordinate = ModuleCoordinate(
                group = module.group,
                module = module.module,
                version = module.version,
            )
            val jvmEnvironments = component.variants
                .mapNotNull { variant ->
                    variant.attributes
                        .getAttribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE)
                        ?.name
                }
                .toSet()

            requests.mergeModuleRequest(coordinate, jvmEnvironments)
        }

    configuration.incoming.resolutionResult.allDependencies
        .mapNotNull { dependency ->
            val selector = dependency.requested as? ModuleComponentSelector
            selector?.exactModuleCoordinate()
        }
        .forEach { coordinate ->
            requests.mergeModuleRequest(coordinate, emptySet())
        }

    return requests
}

private fun classifierJvmEnvironments(module: ModuleRequest): Set<String> {
    val jvmEnvironments = module.jvmEnvironments.toMutableSet()

    when {
        module.coordinate.version.endsWith("-jre") -> {
            jvmEnvironments.add(TargetJvmEnvironment.STANDARD_JVM)
        }
        module.coordinate.version.endsWith("-android") -> {
            jvmEnvironments.add(TargetJvmEnvironment.ANDROID)
        }
    }

    return jvmEnvironments
}

private fun MutableMap<ModuleCoordinate, ModuleRequest>.mergeModuleRequest(
    coordinate: ModuleCoordinate,
    jvmEnvironments: Set<String>,
) {
    merge(
        coordinate,
        ModuleRequest(
            coordinate = coordinate,
            jvmEnvironments = jvmEnvironments,
        ),
    ) { existing, new ->
        ModuleRequest(
            coordinate = existing.coordinate,
            jvmEnvironments = existing.jvmEnvironments + new.jvmEnvironments,
        )
    }
}

private fun MutableMap<ModuleCoordinate, ModuleRequest>.merge(
    requests: Map<ModuleCoordinate, ModuleRequest>,
) {
    requests.forEach { (coordinate, request) ->
        mergeModuleRequest(coordinate, request.jvmEnvironments)
    }
}

private fun MutableMap<ModuleRequestScope, ScopedModuleRequests>.merge(
    project: Project,
    owner: ConfigurationOwner,
    requests: Map<ModuleCoordinate, ModuleRequest>,
) {
    val scope = ModuleRequestScope(project.path, owner)
    val scopedRequests = getOrPut(scope) {
        ScopedModuleRequests(
            project = project,
            owner = owner,
            requests = mutableMapOf(),
        )
    }

    scopedRequests.requests.merge(requests)
}

private fun Project.createDependency(owner: ConfigurationOwner, notation: String): Dependency {
    return when (owner) {
        ConfigurationOwner.BUILDSCRIPT -> buildscript.dependencies.create(notation)
        ConfigurationOwner.PROJECT -> dependencies.create(notation)
    }
}

private fun Project.detachedConfiguration(
    owner: ConfigurationOwner,
    dependency: Dependency,
): Configuration {
    return when (owner) {
        ConfigurationOwner.BUILDSCRIPT -> {
            buildscript.configurations.detachedConfiguration(dependency)
        }
        ConfigurationOwner.PROJECT -> configurations.detachedConfiguration(dependency)
    }
}

private fun Configuration.configureExternalRuntimeAttributes(project: Project) {
    attributes {
        attribute(
            Usage.USAGE_ATTRIBUTE,
            project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
        )
        attribute(
            Bundling.BUNDLING_ATTRIBUTE,
            project.objects.named(Bundling::class.java, Bundling.EXTERNAL),
        )
    }
}

private fun Configuration.configureExternalRuntimeJarAttributes(project: Project) {
    configureExternalRuntimeAttributes(project)
    attributes {
        attribute(
            Category.CATEGORY_ATTRIBUTE,
            project.objects.named(Category::class.java, Category.LIBRARY),
        )
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
    }
}

private fun Configuration.configureMetadataProbeAttributes(
    project: Project,
    category: String?,
) {
    configureExternalRuntimeAttributes(project)
    attributes {
        if (category != null) {
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, category),
            )
        }
        if (category == Category.LIBRARY) {
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
            )
        }
    }
}

private fun detachedClassifierConfiguration(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    classifier: String,
    jvmEnvironment: String?,
): Configuration {
    val dependency = project.createDependency(
        owner = owner,
        notation = "${module.group}:${module.module}:${module.version}:$classifier@jar",
    )
    return project.detachedConfiguration(owner, dependency).apply {
        isTransitive = false
        configureExternalRuntimeJarAttributes(project)
        attributes {
            if (jvmEnvironment != null) {
                attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    project.objects.named(TargetJvmEnvironment::class.java, jvmEnvironment),
                )
            }
        }
    }
}

private fun resolveDocumentationVariantArtifacts(
    project: Project,
    configuration: Configuration,
    docsType: String,
    kotlinPlatformType: String?,
) {
    val artifacts = configuration.incoming.artifactView {
        /*
         * Documentation artifacts are optional. Lenient mode keeps libraries that
         * publish no sources or javadocs from failing the task, while dependency
         * verification failures for artifacts that do resolve still fail.
         */
        lenient(true)
        withVariantReselection()
        componentFilter { componentId ->
            componentId is ModuleComponentIdentifier
        }
        attributes {
            attribute(
                Usage.USAGE_ATTRIBUTE,
                project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
            )
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, Category.DOCUMENTATION),
            )
            attribute(
                Bundling.BUNDLING_ATTRIBUTE,
                project.objects.named(Bundling::class.java, Bundling.EXTERNAL),
            )
            attribute(
                DocsType.DOCS_TYPE_ATTRIBUTE,
                project.objects.named(DocsType::class.java, docsType),
            )
            if (kotlinPlatformType != null) {
                /*
                 * Kotlin multiplatform modules can publish both JVM and Android
                 * JVM documentation variants. A generic documentation request can
                 * be ambiguous, so resolve the common platform values explicitly
                 * as well. Duplicate files are harmless; Gradle verification
                 * metadata is keyed by component/artifact.
                 */
                attribute(kotlinPlatformTypeAttribute, kotlinPlatformType)
            }
        }
    }.artifacts

    artifacts.artifactFiles.forceResolveFiles()
}

private fun resolveDocumentationArtifactsForCommonPlatforms(
    project: Project,
    configuration: Configuration,
    docsType: String,
) {
    val kotlinPlatformTypes = listOf(null, "jvm", "androidJvm", "common")
    kotlinPlatformTypes.forEach { kotlinPlatformType ->
        resolveDocumentationVariantArtifacts(
            project = project,
            configuration = configuration,
            docsType = docsType,
            kotlinPlatformType = kotlinPlatformType,
        )
    }
}

private fun resolveClassifierDocumentationArtifactFiles(
    project: Project,
    owner: ConfigurationOwner,
    request: ModuleRequest,
    classifier: String,
    jvmEnvironment: String?,
): Set<File> {
    val configuration = detachedClassifierConfiguration(
        project = project,
        owner = owner,
        module = request.coordinate,
        classifier = classifier,
        jvmEnvironment = jvmEnvironment,
    )

    val artifacts = configuration.incoming.artifactView {
        /*
         * Classifier artifacts are optional. Android Studio can request plain
         * Maven classifiers even when Gradle selects richer documentation
         * variants, so resolve the classifiers explicitly without failing for
         * modules that do not publish them.
         */
        lenient(true)
        componentFilter { componentId ->
            componentId is ModuleComponentIdentifier
        }
    }.artifacts

    return artifacts.artifactFiles.resolvedFiles()
}

private fun resolveClassifierDocumentationArtifactCount(
    project: Project,
    owner: ConfigurationOwner,
    request: ModuleRequest,
    classifier: String,
): Int {
    val files = resolveClassifierDocumentationArtifactFiles(
        project = project,
        owner = owner,
        request = request,
        classifier = classifier,
        jvmEnvironment = null,
    ).toMutableSet()

    if (files.isEmpty()) {
        classifierJvmEnvironments(request).forEach { jvmEnvironment ->
            files += resolveClassifierDocumentationArtifactFiles(
                project = project,
                owner = owner,
                request = request,
                classifier = classifier,
                jvmEnvironment = jvmEnvironment,
            )
        }
    }

    return files.size
}

private fun resolveMetadataDocumentationArtifactCount(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    artifact: ModuleMetadataDocumentationArtifact,
): Int {
    logger.info(
        "Resolving ${project.path}:${owner.label} metadata documentation artifact " +
            module.notation(":${artifact.classifier}@jar"),
    )
    val request = ModuleRequest(
        coordinate = module,
        jvmEnvironments = emptySet(),
    )
    return resolveClassifierDocumentationArtifactCount(
        project = project,
        owner = owner,
        request = request,
        classifier = artifact.classifier,
    )
}

private fun resolveOptionalMetadataDocumentationArtifactCount(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    artifact: ModuleMetadataDocumentationArtifact,
): Int {
    return try {
        resolveMetadataDocumentationArtifactCount(
            project = project,
            owner = owner,
            module = module,
            artifact = artifact,
        )
    } catch (exception: Exception) {
        if (exception.isDependencyVerificationFailure()) {
            throw exception
        }

        logger.info(
            "Skipping optional metadata documentation artifact " +
                module.notation(":${artifact.classifier}@jar") +
                " because it could not be resolved.",
            exception,
        )
        0
    }
}

private fun resolveMetadataDocumentationArtifactOnce(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    artifact: ModuleMetadataDocumentationArtifact,
    resolvedDocumentationArtifacts: MutableSet<ScopedDocumentationArtifactKey>,
    stats: ResolveStats,
) {
    val key = ScopedDocumentationArtifactKey(
        projectPath = project.path,
        owner = owner,
        coordinate = module,
        classifier = artifact.classifier,
    )
    if (!resolvedDocumentationArtifacts.add(key)) {
        return
    }

    val resolvedArtifactCount = resolveOptionalMetadataDocumentationArtifactCount(
        project = project,
        owner = owner,
        module = module,
        artifact = artifact,
    )
    stats.metadataDocumentationArtifactCount += resolvedArtifactCount
    when (artifact.docsType) {
        DocsType.SOURCES -> stats.sourceClassifierArtifactCount += resolvedArtifactCount
        DocsType.JAVADOC -> stats.javadocClassifierArtifactCount += resolvedArtifactCount
    }
}

private fun resolveOptionalModuleArtifactFiles(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    extension: String,
): Set<File> {
    val failures = mutableListOf<Throwable>()
    listOf(
        Category.LIBRARY,
        Category.REGULAR_PLATFORM,
        Category.ENFORCED_PLATFORM,
        null,
    ).forEach { category ->
        try {
            return resolveOptionalModuleArtifactFiles(
                project = project,
                owner = owner,
                module = module,
                extension = extension,
                category = category,
            )
        } catch (exception: Exception) {
            if (exception.isDependencyVerificationFailure()) {
                throw exception
            }
            failures.add(exception)
        }
    }

    logger.info(
        "Skipping optional dependency metadata artifact " +
            module.notation("@$extension") +
            " because it could not be resolved with any supported attribute profile.",
        failures.lastOrNull(),
    )
    return emptySet()
}

private fun resolveOptionalModuleArtifactFiles(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    extension: String,
    category: String?,
): Set<File> {
    val dependency = project.createDependency(
        owner = owner,
        notation = module.notation("@$extension"),
    )
    val configuration = project.detachedConfiguration(owner, dependency).apply {
        isTransitive = false
        configureMetadataProbeAttributes(project, category)
    }

    val artifacts = configuration.incoming.artifactView {
        /*
         * Metadata formats are repository-dependent. Maven POMs are usually
         * present, Gradle module metadata is optional, and Ivy/artifact-only
         * repositories can legitimately provide neither. Missing optional
         * metadata files are ignored; verification failures are fail-closed.
         */
        lenient(true)
        componentFilter { componentId ->
            componentId is ModuleComponentIdentifier
        }
    }.artifacts

    artifacts.failures.forEach { failure ->
        when {
            failure.isDependencyVerificationFailure() -> {
                throw failure
            }
            failure.isMissingOptionalArtifactFailure() -> {
                Unit
            }
            else -> {
                throw GradleException(
                    "Could not resolve optional dependency metadata artifact " +
                        module.notation("@$extension"),
                    failure,
                )
            }
        }
    }

    return artifacts.artifacts
        .map { artifact -> artifact.file }
        .toSet()
}

private fun resolveDependencyMetadataArtifacts(
    scopedRequests: Collection<ScopedModuleRequests>,
    stats: ResolveStats,
) {
    val pending = ArrayDeque<ScopedModuleCoordinate>()
    scopedRequests.sortedWith(
        compareBy<ScopedModuleRequests> { it.project.path }
            .thenBy { it.owner.label },
    ).forEach { scopedRequest ->
        scopedRequest.requests.keys.sortedWith(
            compareBy<ModuleCoordinate> { it.group }
                .thenBy { it.module }
                .thenBy { it.version },
        ).forEach { coordinate ->
            pending.add(
                ScopedModuleCoordinate(
                    project = scopedRequest.project,
                    owner = scopedRequest.owner,
                    coordinate = coordinate,
                ),
            )
        }
    }

    val seen = mutableSetOf<ScopedModuleCoordinateKey>()
    val resolvedDocumentationArtifacts = mutableSetOf<ScopedDocumentationArtifactKey>()
    while (!pending.isEmpty()) {
        val request = pending.removeFirst()
        val key = ScopedModuleCoordinateKey(
            projectPath = request.project.path,
            owner = request.owner,
            coordinate = request.coordinate,
        )
        if (!seen.add(key)) {
            continue
        }

        logger.info(
            "Resolving ${request.project.path}:${request.owner.label} " +
                request.coordinate.notation("@module"),
        )
        val moduleMetadataFiles = resolveOptionalModuleArtifactFiles(
            project = request.project,
            owner = request.owner,
            module = request.coordinate,
            extension = "module",
        )
        stats.dependencyMetadataArtifactCount += moduleMetadataFiles.size

        logger.info(
            "Resolving ${request.project.path}:${request.owner.label} " +
                request.coordinate.notation("@pom"),
        )
        val pomFiles = resolveOptionalModuleArtifactFiles(
            project = request.project,
            owner = request.owner,
            module = request.coordinate,
            extension = "pom",
        )
        stats.dependencyMetadataArtifactCount += pomFiles.size

        listOf(
            ModuleMetadataDocumentationArtifact(
                classifier = "sources",
                docsType = DocsType.SOURCES,
            ),
            ModuleMetadataDocumentationArtifact(
                classifier = "javadoc",
                docsType = DocsType.JAVADOC,
            ),
        ).forEach { artifact ->
            resolveMetadataDocumentationArtifactOnce(
                project = request.project,
                owner = request.owner,
                module = request.coordinate,
                artifact = artifact,
                resolvedDocumentationArtifacts = resolvedDocumentationArtifacts,
                stats = stats,
            )
        }

        val parsedModuleMetadata = moduleMetadataFiles.map { moduleMetadataFile ->
            parseGradleModuleMetadata(
                moduleMetadataFile = moduleMetadataFile,
                currentModule = request.coordinate,
            )
        }
        parsedModuleMetadata
            .flatMap { moduleMetadata -> moduleMetadata.documentationArtifacts }
            .sortedWith(
                compareBy<ModuleMetadataDocumentationArtifact> { it.docsType }
                    .thenBy { it.classifier },
            )
            .forEach { artifact ->
                resolveMetadataDocumentationArtifactOnce(
                    project = request.project,
                    owner = request.owner,
                    module = request.coordinate,
                    artifact = artifact,
                    resolvedDocumentationArtifacts = resolvedDocumentationArtifacts,
                    stats = stats,
                )
            }
        val moduleMetadataCoordinates = parsedModuleMetadata
            .flatMap { moduleMetadata -> moduleMetadata.dependencies }
        val pomMetadataCoordinates = pomFiles
            .flatMap { pomFile ->
                parseMavenPomMetadata(
                    pomFile = pomFile,
                    currentModule = request.coordinate,
                )
            }
        (moduleMetadataCoordinates + pomMetadataCoordinates)
            .sortedWith(
                compareBy<ModuleCoordinate> { it.group }
                    .thenBy { it.module }
                    .thenBy { it.version },
            )
            .forEach { discoveredCoordinate ->
                pending.add(
                    ScopedModuleCoordinate(
                        project = request.project,
                        owner = request.owner,
                        coordinate = discoveredCoordinate,
                    ),
                )
            }
    }
}

private fun Project.withProjectGradleDistributionsRepository(resolve: () -> Unit) {
    val temporaryRepository = repositories.addGradleDistributionsRepository(
        "${ScriptConfig.GRADLE_DISTRIBUTIONS_REPOSITORY_NAME} for dependency verification",
    )

    try {
        resolve()
    } finally {
        repositories.remove(temporaryRepository)
    }
}

private fun resolveGradleSourceDistribution(project: Project) {
    val gradleVersion = project.gradle.gradleVersion
    fun resolve() {
        val dependency = project.dependencies.create("gradle:gradle:$gradleVersion:src@zip")
        val configuration = project.configurations.detachedConfiguration(dependency)
        configuration.isTransitive = false
        configuration.forceResolveFiles()
    }

    if (repositoriesMode == RepositoriesMode.PREFER_PROJECT) {
        project.withProjectGradleDistributionsRepository(::resolve)
    } else {
        resolve()
    }
}

private fun resolveGradleEmbeddedGroovyModule(
    project: Project,
    stats: ResolveStats,
    module: ModuleCoordinate,
) {
    val moduleMetadataFiles = resolveOptionalModuleArtifactFiles(
        project = project,
        owner = ConfigurationOwner.PROJECT,
        module = module,
        extension = "module",
    )
    val pomFiles = resolveOptionalModuleArtifactFiles(
        project = project,
        owner = ConfigurationOwner.PROJECT,
        module = module,
        extension = "pom",
    )
    val request = ModuleRequest(
        coordinate = module,
        jvmEnvironments = emptySet(),
    )
    val sourceArtifactCount = resolveClassifierDocumentationArtifactCount(
        project = project,
        owner = ConfigurationOwner.PROJECT,
        request = request,
        classifier = "sources",
    )
    val javadocArtifactCount = resolveClassifierDocumentationArtifactCount(
        project = project,
        owner = ConfigurationOwner.PROJECT,
        request = request,
        classifier = "javadoc",
    )

    stats.dependencyMetadataArtifactCount += moduleMetadataFiles.size + pomFiles.size
    stats.sourceClassifierArtifactCount += sourceArtifactCount
    stats.javadocClassifierArtifactCount += javadocArtifactCount
    stats.gradleEmbeddedLibraryArtifactCount +=
        moduleMetadataFiles.size + pomFiles.size + sourceArtifactCount + javadocArtifactCount
}

private fun gradleEmbeddedGroovyModules(): List<ModuleCoordinate> {
    val groovyVersion = GroovySystem.getVersion()
    val groovyJar = File(
        GroovySystem::class.java.protectionDomain.codeSource.location.toURI(),
    )
    val groovyLibraryDirectory = groovyJar.parentFile ?: return listOf(
        ModuleCoordinate(
            group = "org.apache.groovy",
            module = "groovy",
            version = groovyVersion,
        ),
    )
    val groovyJarNameRegex = Regex("""(groovy(?:-[A-Za-z0-9]+)?)-${Regex.escape(groovyVersion)}\.jar""")

    return groovyLibraryDirectory.listFiles()
        .orEmpty()
        .mapNotNull { file ->
            val module = groovyJarNameRegex.matchEntire(file.name)?.groupValues?.get(1)
                ?: return@mapNotNull null
            ModuleCoordinate(
                group = "org.apache.groovy",
                module = module,
                version = groovyVersion,
            )
        }
        .distinct()
        .sortedBy { module -> module.module }
}

private fun resolveGradleEmbeddedGroovyArtifacts(project: Project, stats: ResolveStats) {
    gradleEmbeddedGroovyModules().forEach { module ->
        logger.info("Resolving Gradle embedded Groovy artifacts for ${module.notation()}")
        resolveGradleEmbeddedGroovyModule(
            project = project,
            stats = stats,
            module = module,
        )
    }
}

private fun resolveClassifierDocumentationArtifacts(
    scopedRequests: Collection<ScopedModuleRequests>,
    stats: ResolveStats,
) {
    scopedRequests.sortedWith(
        compareBy<ScopedModuleRequests> { it.project.path }
            .thenBy { it.owner.label },
    ).forEach { scopedRequest ->
        scopedRequest.requests.values.sortedWith(
            compareBy<ModuleRequest> { it.coordinate.group }
                .thenBy { it.coordinate.module }
                .thenBy { it.coordinate.version },
        )
            .forEach { request ->
                val module = request.coordinate
                logger.info(
                    "Resolving ${scopedRequest.displayPrefix()} " +
                        "${module.group}:${module.module}:${module.version}:sources@jar",
                )
                stats.sourceClassifierArtifactCount += resolveClassifierDocumentationArtifactCount(
                    project = scopedRequest.project,
                    owner = scopedRequest.owner,
                    request = request,
                    classifier = "sources",
                )

                logger.info(
                    "Resolving ${scopedRequest.displayPrefix()} " +
                        "${module.group}:${module.module}:${module.version}:javadoc@jar",
                )
                stats.javadocClassifierArtifactCount += resolveClassifierDocumentationArtifactCount(
                    project = scopedRequest.project,
                    owner = scopedRequest.owner,
                    request = request,
                    classifier = "javadoc",
                )
            }
    }
}

private fun resolveHostToolArtifact(
    project: Project,
    owner: ConfigurationOwner,
    module: ModuleCoordinate,
    classifier: String?,
    extension: String,
    transitive: Boolean,
): ResolvedDetachedArtifacts {
    val classifierSegment = classifier?.let { ":$it" } ?: ""
    val notation = if (classifier == null && transitive) {
        "${module.group}:${module.module}:${module.version}"
    } else {
        "${module.group}:${module.module}:${module.version}$classifierSegment@$extension"
    }
    val dependency = project.createDependency(
        owner = owner,
        notation = notation,
    )
    val configuration = project.detachedConfiguration(owner, dependency).apply {
        isTransitive = transitive
    }

    val artifacts = configuration.incoming.artifactView {
        /*
         * AGP and Gradle plugins can resolve platform-specific host tools from
         * detached configurations during build tasks.
         * Resolve only reviewed host tool rules, not arbitrary native/client platform variants.
         */
        componentFilter { componentId ->
            componentId is ModuleComponentIdentifier
        }
    }.artifacts

    return ResolvedDetachedArtifacts(
        files = artifacts.artifactFiles.resolvedFiles(),
        moduleRequests = resolvedModuleRequests(configuration),
    )
}

private fun hostToolRequests(
    requests: Map<ModuleCoordinate, ModuleRequest>,
): List<HostToolRequest> {
    return requests.keys
        .flatMap { markerCoordinate ->
            hostToolRules
                .filter { rule -> rule.marker.matches(markerCoordinate) }
                .map { rule ->
                    HostToolRequest(
                        rule = rule,
                        coordinate = ModuleCoordinate(
                            group = rule.artifact.group,
                            module = rule.artifact.module,
                            version = markerCoordinate.version,
                        ),
                    )
                }
        }
        .distinct()
        .sortedWith(
            compareBy<HostToolRequest> { it.coordinate.group }
                .thenBy { it.coordinate.module }
                .thenBy { it.coordinate.version }
                .thenBy { it.rule.extension }
                .thenBy { it.rule.classifierSortKey() },
        )
}

private fun resolveHostToolArtifacts(
    scopedRequests: Collection<ScopedModuleRequests>,
    stats: ResolveStats,
) {
    scopedRequests.sortedWith(
        compareBy<ScopedModuleRequests> { it.project.path }
            .thenBy { it.owner.label },
    ).forEach { scopedRequest ->
        hostToolRequests(scopedRequest.requests).forEach { request ->
            val module = request.coordinate
            request.rule.classifiers.forEach { classifier ->
                val extension = request.rule.extension
                val classifierSegment = classifier?.let { ":$it" } ?: ""
                val artifactNotation = module.notation("$classifierSegment@$extension")
                logger.info(
                    "Resolving ${scopedRequest.displayPrefix()} " +
                        artifactNotation,
                )
                val resolvedArtifacts = resolveHostToolArtifact(
                    project = scopedRequest.project,
                    owner = scopedRequest.owner,
                    module = module,
                    classifier = classifier,
                    extension = extension,
                    transitive = request.rule.transitive,
                )
                stats.hostToolArtifactCount += resolvedArtifacts.files.size
                scopedRequest.requests.merge(resolvedArtifacts.moduleRequests)
            }
        }
    }
}

private fun resolveStandaloneConfigurationArtifacts(
    project: Project,
    configuration: Configuration,
): Map<ModuleCoordinate, ModuleRequest> {
    resolvePrimaryArtifacts(configuration)
    val requests = resolvedModuleRequests(configuration)

    resolveDocumentationArtifactsForCommonPlatforms(project, configuration, DocsType.SOURCES)
    resolveDocumentationArtifactsForCommonPlatforms(project, configuration, DocsType.JAVADOC)

    return requests
}

private fun resolveConfigurationArtifacts(
    project: Project,
    owner: ConfigurationOwner,
    configuration: Configuration,
): ResolvedConfigurationArtifacts {
    try {
        return ResolvedConfigurationArtifacts(
            moduleRequests = resolveStandaloneConfigurationArtifacts(project, configuration),
            usedExternalModuleFallback = false,
        )
    } catch (exception: Exception) {
        if (
            exception.isDependencyVerificationFailure() ||
            !exception.isStandaloneResolutionTimingFailure()
        ) {
            throw exception
        }

        logger.lifecycle(
            "Resolving ${configuration.displayName(project, owner)} through an " +
                "external-module-only copy because the original configuration " +
                "depends on task outputs " +
                "that are not available to this standalone resolver.",
        )

        val fallbackConfiguration = externalModuleConfigurationCopy(configuration)
        try {
            return ResolvedConfigurationArtifacts(
                moduleRequests = resolveStandaloneConfigurationArtifacts(
                    project = project,
                    configuration = fallbackConfiguration,
                ),
                usedExternalModuleFallback = true,
            )
        } catch (fallbackException: Exception) {
            if (fallbackException.isDependencyVerificationFailure()) {
                throw fallbackException
            }

            throw GradleException(
                "Could not resolve ${configuration.displayName(project, owner)} directly or " +
                    "through an external-module-only fallback.",
                fallbackException,
            ).also { it.addSuppressed(exception) }
        }
    }
}

private fun resolveOwnerConfigurations(
    project: Project,
    owner: ConfigurationOwner,
    configurations: Iterable<Configuration>,
    moduleRequests: MutableMap<ModuleRequestScope, ScopedModuleRequests>,
    stats: ResolveStats,
) {
    configurations.resolvableConfigurations().forEach { configuration ->
        logger.info("Resolving ${configuration.displayName(project, owner)}")
        val resolvedArtifacts = resolveConfigurationArtifacts(
            project = project,
            owner = owner,
            configuration = configuration,
        )
        stats.primaryConfigurationCount += 1
        if (resolvedArtifacts.usedExternalModuleFallback) {
            stats.externalModuleFallbackConfigurationCount += 1
        }

        moduleRequests.merge(
            project = project,
            owner = owner,
            requests = resolvedArtifacts.moduleRequests,
        )
    }
}

private fun resolveProjectConfigurations(
    project: Project,
    moduleRequests: MutableMap<ModuleRequestScope, ScopedModuleRequests>,
    stats: ResolveStats,
) {
    resolveOwnerConfigurations(
        project = project,
        owner = ConfigurationOwner.BUILDSCRIPT,
        configurations = project.buildscript.configurations,
        moduleRequests = moduleRequests,
        stats = stats,
    )
    resolveOwnerConfigurations(
        project = project,
        owner = ConfigurationOwner.PROJECT,
        configurations = project.configurations,
        moduleRequests = moduleRequests,
        stats = stats,
    )
}

/**
 * Derives the Gradle artifact-only notation for a pinned artifact file name so it
 * can be re-resolved exactly as recorded. Returns null when the file name does not
 * follow the `<module>-<version>[-<classifier>].<ext>` convention, in which case the
 * artifact cannot be mapped back to a request and is skipped.
 */
private fun pinnedArtifactNotation(coordinate: ModuleCoordinate, fileName: String): String? {
    val prefix = "${coordinate.module}-${coordinate.version}"
    if (!fileName.startsWith(prefix)) {
        return null
    }

    val remainder = fileName.removePrefix(prefix)
    val (classifier, extension) = when {
        remainder.startsWith(".") -> null to remainder.substring(1)
        remainder.startsWith("-") -> {
            val body = remainder.substring(1)
            val extensionSeparator = body.lastIndexOf('.')
            if (extensionSeparator <= 0) {
                return null
            }
            body.substring(0, extensionSeparator) to body.substring(extensionSeparator + 1)
        }
        else -> return null
    }

    if (extension.isEmpty()) {
        return null
    }

    val classifierSegment = classifier?.let { ":$it" } ?: ""
    return "${coordinate.group}:${coordinate.module}:${coordinate.version}" +
        "$classifierSegment@$extension"
}

private fun resolvePinnedArtifact(
    project: Project,
    owner: ConfigurationOwner,
    notation: String,
): Int {
    val dependency = project.createDependency(owner, notation)
    val configuration = project.detachedConfiguration(owner, dependency).apply {
        isTransitive = false
    }

    return try {
        val artifacts = configuration.incoming.artifactView {
            /*
             * Pinned artifacts are re-resolved only so strict verification
             * re-checks the existing metadata. Lenient mode tolerates a pinned
             * artifact a repository no longer publishes, while dependency
             * verification failures for artifacts that still resolve remain
             * fail-closed.
             */
            lenient(true)
            componentFilter { componentId ->
                componentId is ModuleComponentIdentifier
            }
        }.artifacts

        artifacts.failures.forEach { failure ->
            if (failure.isDependencyVerificationFailure()) {
                throw failure
            }
        }

        artifacts.artifacts.size
    } catch (exception: Exception) {
        if (exception.isDependencyVerificationFailure()) {
            throw exception
        }

        /*
         * A pinned artifact can require variant attributes that this standalone
         * probe cannot infer (for example multi-target modules such as Guava that
         * need a target JVM environment). Those artifacts are already resolved and
         * verified through the live dependency graph, so re-verification here is
         * best-effort: tolerate resolution problems, but never a verification
         * failure.
         */
        logger.info(
            "Skipping pinned verification artifact $notation because the standalone " +
                "probe could not resolve it.",
            exception,
        )
        0
    }
}

/**
 * Re-resolves exactly the artifacts already recorded in the verification metadata so
 * strict verification re-checks them, without inferring source, javadoc, or other
 * classifier requests that the live dependency graph never makes. This keeps the
 * resolver and the checker symmetric: the resolver records what the graph resolves,
 * and the checker re-verifies precisely that recorded set rather than expanding
 * metadata-only components (such as multiplatform relocation targets) into artifacts
 * that were never pinned.
 */
private fun verifyPinnedVerificationMetadataArtifacts(
    project: Project,
    stats: ResolveStats,
) {
    val metadataFile = project.rootProject.projectDir.resolve(ScriptConfig.METADATA_PATH)
    val pinnedComponents = parseVerificationMetadataPinnedComponents(metadataFile)
    if (pinnedComponents.isEmpty()) {
        return
    }

    val seen = mutableSetOf<String>()
    pinnedComponents
        .sortedWith(
            compareBy<PinnedComponent> { it.coordinate.group }
                .thenBy { it.coordinate.module }
                .thenBy { it.coordinate.version },
        )
        .forEach { component ->
            component.artifactNames.sorted().forEach { artifactName ->
                val notation = pinnedArtifactNotation(component.coordinate, artifactName)
                if (notation == null) {
                    logger.info(
                        "Skipping pinned verification artifact $artifactName for " +
                            "${component.coordinate.notation()} because its notation could " +
                            "not be derived.",
                    )
                    return@forEach
                }
                if (!seen.add(notation)) {
                    return@forEach
                }

                logger.info("Verifying pinned $notation")
                /*
                 * The metadata file does not record whether a component came from a
                 * project or buildscript repository, so re-verify through the project
                 * scope first and fall back to the buildscript scope only when the
                 * project repositories cannot serve the artifact.
                 */
                val projectArtifactCount = resolvePinnedArtifact(
                    project = project.rootProject,
                    owner = ConfigurationOwner.PROJECT,
                    notation = notation,
                )
                stats.pinnedVerificationArtifactCount += if (projectArtifactCount > 0) {
                    projectArtifactCount
                } else {
                    resolvePinnedArtifact(
                        project = project.rootProject,
                        owner = ConfigurationOwner.BUILDSCRIPT,
                        notation = notation,
                    )
                }
            }
        }
}

private fun minimalVerificationMetadata(): String {
    return """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<verification-metadata xmlns="${ScriptConfig.DEPENDENCY_VERIFICATION_NAMESPACE}">
        |   <components>
        |   </components>
        |</verification-metadata>
        |
    """.trimMargin()
}

private fun currentInitScriptFile(): File {
    return gradle.startParameter.allInitScripts.firstOrNull { initScript ->
        initScript.name == ScriptConfig.SCRIPT_FILE_NAME
    } ?: throw GradleException(
        "Could not find ${ScriptConfig.SCRIPT_FILE_NAME} in Gradle init scripts",
    )
}

private fun Project.resolveBuildSrcVerificationMetadataComponents(): Set<ModuleCoordinate> {
    if (!writeVerificationMetadataRequested || rootProject.projectDir.name == "buildSrc") {
        return emptySet()
    }

    val buildSrcDir = rootProject.projectDir.resolve("buildSrc")
    if (!buildSrcDir.isDirectory) {
        return emptySet()
    }

    val wrapper = rootProject.projectDir.resolve(
        if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew",
    )
    if (!wrapper.isFile) {
        return emptySet()
    }

    val metadataFile = buildSrcDir.resolve(ScriptConfig.METADATA_PATH)
    val metadataDirectory = metadataFile.parentFile
    val metadataDirectoryExisted = metadataDirectory.isDirectory
    val originalMetadata = metadataFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    metadataDirectory.mkdirs()
    metadataFile.writeText(minimalVerificationMetadata(), Charsets.UTF_8)

    try {
        val writeVerificationMetadata =
            gradle.startParameter.writeDependencyVerifications.joinToString(",")
        val process = ProcessBuilder(
            wrapper.absolutePath,
            "--no-daemon",
            "--no-configuration-cache",
            "-I",
            currentInitScriptFile().absolutePath,
            "--write-verification-metadata",
            writeVerificationMetadata,
            ScriptConfig.RESOLVE_TASK_NAME,
        )
            .directory(buildSrcDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Could not resolve buildSrc dependency verification artifacts.\n$output",
            )
        }

        return parseVerificationMetadataComponents(metadataFile)
    } finally {
        if (originalMetadata != null) {
            metadataFile.writeText(originalMetadata, Charsets.UTF_8)
        } else {
            metadataFile.delete()
            if (!metadataDirectoryExisted) {
                metadataDirectory.delete()
            }
        }
    }
}

private fun Project.registerDependencyVerificationResolverTask(
    taskName: String,
    taskDescription: String,
) {
    tasks.register(taskName) {
        group = "verification"
        description = taskDescription
        notCompatibleWithConfigurationCache(
            "Dependency verification artifact resolution intentionally inspects " +
                "configurations across projects.",
        )

        doLast {
            val stats = ResolveStats()
            val moduleRequests =
                mutableMapOf<ModuleRequestScope, ScopedModuleRequests>()

            rootProject.allprojects.sortedBy { it.path }.forEach { project ->
                resolveProjectConfigurations(project, moduleRequests, stats)
            }
            val buildSrcRequests = resolveBuildSrcVerificationMetadataComponents()
                .associateWith { coordinate ->
                    ModuleRequest(
                        coordinate = coordinate,
                        jvmEnvironments = emptySet(),
                    )
                }
            moduleRequests.merge(
                project = rootProject,
                owner = ConfigurationOwner.BUILDSCRIPT,
                requests = buildSrcRequests,
            )
            moduleRequests.merge(
                project = rootProject,
                owner = ConfigurationOwner.PROJECT,
                requests = buildSrcRequests,
            )

            resolveClassifierDocumentationArtifacts(
                scopedRequests = moduleRequests.values,
                stats = stats,
            )

            resolveHostToolArtifacts(
                scopedRequests = moduleRequests.values,
                stats = stats,
            )

            resolveDependencyMetadataArtifacts(
                scopedRequests = moduleRequests.values,
                stats = stats,
            )

            verifyPinnedVerificationMetadataArtifacts(rootProject, stats)

            resolveGradleEmbeddedGroovyArtifacts(rootProject, stats)

            logger.info("Resolving Gradle ${rootProject.gradle.gradleVersion} source distribution")
            resolveGradleSourceDistribution(rootProject)
            stats.gradleSourceDistributionCount += 1

            logger.lifecycle(
                "Resolved dependency verification artifacts: " +
                    "${stats.primaryConfigurationCount} primary configuration(s), " +
                    "${stats.externalModuleFallbackConfigurationCount} " +
                    "external-module fallback configuration(s), " +
                    "${stats.dependencyMetadataArtifactCount} " +
                    "dependency metadata artifact(s), " +
                    "${stats.sourceClassifierArtifactCount} source classifier artifact(s), " +
                    "${stats.javadocClassifierArtifactCount} javadoc classifier artifact(s), " +
                    "${stats.metadataDocumentationArtifactCount} " +
                    "metadata documentation artifact(s), " +
                    "${stats.gradleEmbeddedLibraryArtifactCount} " +
                    "Gradle embedded library artifact(s), " +
                    "${stats.hostToolArtifactCount} host tool artifact(s), " +
                    "${stats.pinnedVerificationArtifactCount} " +
                    "pinned verification artifact(s), " +
                    "${stats.gradleSourceDistributionCount} Gradle source distribution(s).",
            )
        }
    }
}

gradle.projectsEvaluated {
    rootProject.registerDependencyVerificationResolverTask(
        ScriptConfig.RESOLVE_TASK_NAME,
        "Resolves build, source, javadoc, classifier, host tool, and Gradle source artifacts " +
            "for dependency verification.",
    )
    rootProject.registerDependencyVerificationResolverTask(
        ScriptConfig.CHECK_TASK_NAME,
        "Checks dependency verification metadata for build, source, javadoc, " +
            "classifier, host tool, " +
            "and Gradle source artifacts.",
    )
}
