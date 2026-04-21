package com.opendash.app.assistant.skills.runtime

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.skills.Skill
import org.junit.jupiter.api.Test

class SkillScriptExtractorTest {

    private val extractor = SkillScriptExtractor()

    @Test
    fun `no scripts when body has no fenced blocks`() {
        val skill = Skill("s", "d", "# Heading\n\nJust prose.")
        assertThat(extractor.extract(skill)).isEmpty()
    }

    @Test
    fun `extract single js block`() {
        val body = """
# Usage

```js
return "hello";
```
""".trimIndent()
        val skill = Skill("greeter", "d", body)

        val scripts = extractor.extract(skill)

        assertThat(scripts).hasSize(1)
        assertThat(scripts[0].language).isEqualTo("js")
        assertThat(scripts[0].skillName).isEqualTo("greeter")
        assertThat(scripts[0].index).isEqualTo(0)
        assertThat(scripts[0].source).isEqualTo("return \"hello\";")
    }

    @Test
    fun `extract multiple js blocks in order`() {
        val body = """
```javascript
const a = 1;
```

some prose

```js
const b = 2;
```
""".trimIndent()
        val skill = Skill("multi", "d", body)

        val scripts = extractor.extract(skill)

        assertThat(scripts).hasSize(2)
        assertThat(scripts[0].source).isEqualTo("const a = 1;")
        assertThat(scripts[0].index).isEqualTo(0)
        assertThat(scripts[0].language).isEqualTo("javascript")
        assertThat(scripts[1].source).isEqualTo("const b = 2;")
        assertThat(scripts[1].index).isEqualTo(1)
        assertThat(scripts[1].language).isEqualTo("js")
    }

    @Test
    fun `ignore non-js fences`() {
        val body = """
```kotlin
val x = 1
```

```bash
echo hi
```

```js
return 42;
```
""".trimIndent()
        val skill = Skill("s", "d", body)

        val scripts = extractor.extract(skill)

        assertThat(scripts).hasSize(1)
        assertThat(scripts[0].source).isEqualTo("return 42;")
    }

    @Test
    fun `ignore language-less fence`() {
        val body = """
```
plain text fence
```
""".trimIndent()
        val skill = Skill("s", "d", body)

        assertThat(extractor.extract(skill)).isEmpty()
    }

    @Test
    fun `ignore blank js block`() {
        val body = """
```js

```
""".trimIndent()
        val skill = Skill("s", "d", body)

        assertThat(extractor.extract(skill)).isEmpty()
    }

    @Test
    fun `unclosed fence truncates extraction`() {
        // Should not crash and should not capture anything from an unclosed
        // fence — otherwise a malformed skill could swallow everything.
        val body = """
```js
const a = 1
// never closed
""".trimIndent()
        val skill = Skill("s", "d", body)

        assertThat(extractor.extract(skill)).isEmpty()
    }

    @Test
    fun `multi-line js body preserved verbatim`() {
        val body = """
```js
function greet(name) {
  return "hello " + name;
}
```
""".trimIndent()
        val skill = Skill("s", "d", body)

        val scripts = extractor.extract(skill)

        assertThat(scripts).hasSize(1)
        assertThat(scripts[0].source).isEqualTo(
            "function greet(name) {\n  return \"hello \" + name;\n}"
        )
    }
}
