package dev.chimeraant.berryforge.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the inline WebGL viewer.
 *
 * The viewer previously shipped with `count: indexCount` — storing the whole index *array*
 * where `gl.drawElements` expects a `GLsizei`. The array coerced to `NaN`, the first frame
 * threw, and `requestAnimationFrame(loop)` was never reached again, so the canvas stayed
 * blank forever. A compiler cannot see this, so it is pinned here.
 */
class ModelViewerHtmlTest {

    @Test
    fun `index count is a number not an array`() {
        val html = inlineViewerHtml("v 0 0 0")
        assertTrue(html.contains("count:indexCount.length"))
        assertFalse(html.contains("count:indexCount,"))
    }

    @Test
    fun `error element exists and is referenced safely`() {
        val html = inlineViewerHtml("v 0 0 0")
        assertTrue("the #err element must exist in the body", html.contains("""<div id="err"></div>"""))
        assertTrue(html.contains("function fail("))
        // The old code dereferenced getElementById('err') inline, which threw a second
        // TypeError when the element was missing.
        assertFalse(html.contains("document.getElementById('err').style"))
    }

    @Test
    fun `aspect ratio is computed from the canvas`() {
        val html = inlineViewerHtml("v 0 0 0")
        assertTrue(html.contains("var asp=canvas.height>0?canvas.width/canvas.height:1"))
    }

    @Test
    fun `render loop surfaces errors instead of dying silently`() {
        val html = inlineViewerHtml("v 0 0 0")
        assertTrue(html.contains("catch(e){fail('Render error: '+e.message);return;}"))
    }

    @Test
    fun `glb is treated as a model format`() {
        assertTrue(ModelFormats.isModelFormat("model.glb"))
        assertTrue(ModelFormats.isBinary("model.glb"))
    }

    @Test
    fun `gltf accessors are read with stride and alignment safety`() {
        val html = inlineViewerHtml("{}")
        // Reading through a DataView honours byteStride (interleaved buffers) and cannot
        // throw the RangeError a misaligned Float32Array view would.
        assertTrue(html.contains("function readAccessor("))
        assertTrue(html.contains("bv.byteStride||"))
        assertTrue(html.contains("dv.getFloat32(p,true)"))
        assertFalse("a raw Float32Array view over an arbitrary offset must be gone", html.contains("new Float32Array(arr.buffer,byteOffset"))
    }

    @Test
    fun `non-indexed gltf emits whole triangles`() {
        val html = inlineViewerHtml("{}")
        assertTrue(html.contains("i+2<vcount;i+=3"))
        assertFalse(html.contains("i%4<3"))
    }

    @Test
    fun `index element type is chosen from the vertex count`() {
        val html = inlineViewerHtml("{}")
        // A mesh with more than 65535 vertices must not silently wrap in a Uint16Array.
        assertTrue(html.contains("use32=maxIdx>65535"))
        assertTrue(html.contains("gl.drawElements(gl.TRIANGLES,mesh.count,mesh.idxType,0)"))
    }

    @Test
    fun `model data is escaped into the script`() {
        val html = inlineViewerHtml("v 0 0 0\n</script><script>alert(1)</script>")
        assertFalse(html.contains("</script><script>alert"))
    }
}
