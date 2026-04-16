package com.opensmarthome.speaker.assistant.skills

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SkillRepositoryTest {

    @TempDir
    lateinit var userDir: File

    private lateinit var registry: SkillRegistry
    private lateinit var repo: SkillRepository

    @BeforeEach
    fun setup() {
        registry = SkillRegistry()
        repo = SkillRepository(registry, userDir)
    }

    @Test
    fun `bundled skill not deletable`() {
        registry.register(Skill("bundled1", "desc", "body", source = "asset:skills/bundled1/SKILL.md"))

        val views = repo.listAll()
        assertThat(views).hasSize(1)
        assertThat(views[0].deletable).isFalse()
    }

    @Test
    fun `installed skill is deletable and removes directory`() {
        val skillDir = File(userDir, "test-skill").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md").apply { writeText("test body") }
        registry.register(Skill(
            "test-skill", "desc", "body",
            source = "installed:${skillFile.absolutePath}"
        ))

        assertThat(repo.listAll()[0].deletable).isTrue()

        val deleted = repo.delete("test-skill")
        assertThat(deleted).isTrue()
        assertThat(registry.get("test-skill")).isNull()
        assertThat(skillDir.exists()).isFalse()
    }

    @Test
    fun `delete bundled returns false`() {
        registry.register(Skill("b", "d", "body", source = "asset:skills/b/SKILL.md"))

        assertThat(repo.delete("b")).isFalse()
        assertThat(registry.get("b")).isNotNull()
    }

    @Test
    fun `delete unknown returns false`() {
        assertThat(repo.delete("nope")).isFalse()
    }
}
