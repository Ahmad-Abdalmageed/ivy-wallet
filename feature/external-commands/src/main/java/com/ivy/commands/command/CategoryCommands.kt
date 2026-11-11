package com.ivy.commands.command

import arrow.core.raise.Raise
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Tag
import com.ivy.data.model.TagId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

/**
 * Lists, adds, changes and deletes categories and tags.
 */
class CategoryCommands @Inject constructor(deps: CommandDeps) : CommandGroup(deps) {
    override val commands = setOf(
        Command.ListCategories,
        Command.AddCategory,
        Command.UpdateCategory,
        Command.DeleteCategory,
        Command.ListTags,
        Command.AddTag,
        Command.UpdateTag,
        Command.DeleteTag,
    )

    override suspend fun Raise<String>.execute(command: Command, args: CommandArgs): JsonElement =
        when (command) {
            Command.ListCategories -> buildJsonArray {
                deps.categoryRepository.findAll().forEach { add(it.toJson()) }
            }

            Command.AddCategory -> addCategory(args)
            Command.UpdateCategory -> updateCategory(args)
            Command.DeleteCategory -> {
                val category = findCategory(required(args, "category"))
                deps.categoryRepository.deleteById(category.id)
                buildJsonObject { put("deleted", category.name.value) }
            }

            Command.ListTags -> buildJsonArray {
                deps.tagRepository.findAll().forEach { add(it.toJson()) }
            }

            Command.AddTag -> addTag(args)
            Command.UpdateTag -> updateTag(args)
            else -> {
                val tag = findTags(listOf(required(args, "tag"))).first()
                deps.tagRepository.deleteById(tag.id)
                buildJsonObject { put("deleted", tag.name.value) }
            }
        }

    private suspend fun Raise<String>.addCategory(args: CommandArgs): JsonElement {
        val name = name(required(args, "name"))
        ensureUnique(deps.categoryRepository.findAll(), name.value, "category") { it.name.value }
        val category = Category(
            id = CategoryId(UUID.randomUUID()),
            name = name,
            color = color(args.string("color")),
            icon = icon(args.string("icon")),
            orderNum = deps.categoryRepository.findMaxOrderNum() + 1,
        )
        deps.categoryRepository.save(category)
        return category.toJson()
    }

    private suspend fun Raise<String>.updateCategory(args: CommandArgs): JsonElement {
        val existing = findCategory(required(args, "category"))
        val newName = args.string("name")?.let { name(it) }
        if (newName != null && !newName.value.equals(existing.name.value, ignoreCase = true)) {
            ensureUnique(deps.categoryRepository.findAll(), newName.value, "category") { it.name.value }
        }
        val category = existing.copy(
            name = newName ?: existing.name,
            color = args.string("color")?.let { color(it) } ?: existing.color,
            icon = args.string("icon")?.let { icon(it) } ?: existing.icon,
        )
        deps.categoryRepository.save(category)
        return category.toJson()
    }

    private suspend fun Raise<String>.addTag(args: CommandArgs): JsonElement {
        val name = name(required(args, "name"))
        ensureUnique(deps.tagRepository.findAll(), name.value, "tag") { it.name.value }
        val tags = deps.tagRepository.findAll()
        val tag = Tag(
            id = TagId(UUID.randomUUID()),
            name = name,
            description = args.string("description"),
            color = color(args.string("color")),
            icon = null,
            orderNum = (tags.maxOfOrNull(Tag::orderNum) ?: 0.0) + 1,
            creationTimestamp = deps.timeProvider.utcNow(),
        )
        deps.tagRepository.save(tag)
        return tag.toJson()
    }

    private suspend fun Raise<String>.updateTag(args: CommandArgs): JsonElement {
        val existing = findTags(listOf(required(args, "tag"))).first()
        val newName = args.string("name")?.let { name(it) }
        if (newName != null && !newName.value.equals(existing.name.value, ignoreCase = true)) {
            ensureUnique(deps.tagRepository.findAll(), newName.value, "tag") { it.name.value }
        }
        val tag = existing.copy(
            name = newName ?: existing.name,
            description = when (val description = args.clearable("description")) {
                null -> existing.description
                CommandArgs.Clear -> null
                is CommandArgs.Value -> description.value
            },
            color = args.string("color")?.let { color(it) } ?: existing.color,
        )
        deps.tagRepository.save(tag)
        return tag.toJson()
    }

    private fun Category.toJson(): JsonObject = buildJsonObject {
        put("id", id.value.toString())
        put("name", name.value)
        put("color", hex(color))
        icon?.let { put("icon", it.id) }
    }

    private fun Tag.toJson(): JsonObject = buildJsonObject {
        put("id", id.value.toString())
        put("name", name.value)
        description?.let { put("description", it) }
        put("color", hex(color))
    }
}
