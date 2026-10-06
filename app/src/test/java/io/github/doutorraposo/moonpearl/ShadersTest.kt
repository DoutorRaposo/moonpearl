package io.github.doutorraposo.moonpearl

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShadersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ini() = Ini("[Graphics]\nOutputMethod = SDL\nLinearFiltering = 0\nShader =\n")

    @Test
    fun plainOptionsUseTheSdlRenderer() {
        val ini = ini()
        assertEquals(Shaders.Choice.Sharp, Shaders.current(ini))
        Shaders.apply(ini, Shaders.Choice.Smooth)
        assertEquals("SDL", ini["Graphics", "OutputMethod"])
        assertEquals(Shaders.Choice.Smooth, Shaders.current(ini))
    }

    @Test
    fun shaderSwitchesToOpenGlEs() {
        val ini = ini()
        val crt = Shaders.Choice.Shader(Shaders.Builtin.CRT.path)
        Shaders.apply(ini, crt)
        assertEquals("OpenGL ES", ini["Graphics", "OutputMethod"])
        assertEquals("shaders/builtin/crt-lottes/crt-lottes.glslp", ini["Graphics", "Shader"])
        assertEquals("0", ini["Graphics", "LinearFiltering"])
        assertEquals(crt, Shaders.current(ini))
        Shaders.apply(ini, Shaders.Choice.Sharp)
        assertEquals("", ini["Graphics", "Shader"])
        assertEquals(Shaders.Choice.Sharp, Shaders.current(ini))
    }

    @Test
    fun aShaderWithoutOpenGlOutputIsIgnored() {
        // Upstream only uses Shader with an OpenGL output method.
        val ini = Ini("[Graphics]\nOutputMethod = SDL\nShader = x.glslp\n")
        assertEquals(Shaders.Choice.Sharp, Shaders.current(ini))
    }

    @Test
    fun missingShaderFallsBackToSharp() {
        val dir = tmp.root
        val ini = ini()
        Shaders.apply(ini, Shaders.Choice.Shader("shaders/custom/crt/crt-geom.glslp"))
        Shaders.validate(ini, dir)
        assertEquals(Shaders.Choice.Sharp, Shaders.current(ini))

        File(dir, "shaders/custom/crt").mkdirs()
        File(dir, "shaders/custom/crt/crt-geom.glslp").writeText("shaders = 1")
        Shaders.apply(ini, Shaders.Choice.Shader("shaders/custom/crt/crt-geom.glslp"))
        Shaders.validate(ini, dir)
        assertEquals(Shaders.Choice.Shader("shaders/custom/crt/crt-geom.glslp"), Shaders.current(ini))
    }

    @Test
    fun importedListsPresetsAndTopLevelShaders() {
        val root = File(tmp.root, "shaders/custom")
        for (path in listOf("crt/crt-geom.glslp", "crt/shaders/crt-geom.glsl", "stock.glsl", "xbr/xbr-lv2.glslp", "readme.png")) {
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        assertEquals(
            listOf("shaders/custom/crt/crt-geom.glslp", "shaders/custom/stock.glsl", "shaders/custom/xbr/xbr-lv2.glslp"),
            Shaders.imported(tmp.root),
        )
        assertEquals("crt/crt-geom", Shaders.displayName("shaders/custom/crt/crt-geom.glslp"))
    }
}
