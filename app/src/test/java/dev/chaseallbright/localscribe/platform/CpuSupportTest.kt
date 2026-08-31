package dev.chaseallbright.localscribe.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuSupportTest {

    // A real ARMv8.2+ Features line (Snapdragon 8-class, the test device).
    private val armv82Features =
        "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid " +
            "asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat " +
            "ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svebitperm"

    // A real ARMv8.0 Features line (Snapdragon 835 class -- the Pixel 2 case).
    private val armv80Features =
        "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32"

    private fun cpuinfo(vararg featureLines: String): String =
        featureLines.joinToString("\n") { line ->
            "processor\t: 0\nBogoMIPS\t: 38.40\n$line\nCPU implementer\t: 0x51\n"
        }

    @Test
    fun `armv8_2 cpu is supported`() {
        assertEquals(
            CpuSupport.Supported,
            CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features))
        )
    }

    @Test
    fun `armv8_0 cpu is unsupported and names both missing features`() {
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(armv80Features))
        assertEquals(CpuSupport.Unsupported(listOf("asimdhp", "asimddp")), result)
    }

    @Test
    fun `fp16 without dotprod names only dotprod`() {
        val features = "Features\t: fp asimd aes pmull crc32 atomics fphp asimdhp asimdrdm"
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(features))
        assertEquals(CpuSupport.Unsupported(listOf("asimddp")), result)
    }

    @Test
    fun `asimdrdm does not satisfy asimddp by substring`() {
        // "asimd", "asimdhp", "asimdrdm" and "asimddp" share prefixes; a contains() check
        // would wrongly pass here.
        val features = "Features\t: fp asimd atomics fphp asimdhp asimdrdm"
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(features))
        assertEquals(CpuSupport.Unsupported(listOf("asimddp")), result)
    }

    @Test
    fun `a feature missing from any core makes the cpu unsupported`() {
        // Intersection, not union: a thread scheduled onto the weaker core would fault.
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features, armv80Features))
        assertEquals(CpuSupport.Unsupported(listOf("asimdhp", "asimddp")), result)
    }

    @Test
    fun `all cores reporting the features is supported`() {
        assertEquals(
            CpuSupport.Supported,
            CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features, armv82Features))
        )
    }

    @Test
    fun `x86_64 emulator is supported because the flag is arm64 only`() {
        // The x86_64 library is built without -march, and this cpuinfo has "flags", not
        // "Features". Without the ABI rule the emulator would be declared unsupported.
        val x86 = "processor\t: 0\nvendor_id\t: GenuineIntel\n" +
            "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep sse2 avx2\n"
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("x86_64", x86))
    }

    @Test
    fun `unreadable cpuinfo fails open`() {
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", null))
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", ""))
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", "   \n  \n"))
    }

    @Test
    fun `cpuinfo without a Features line fails open`() {
        val noFeatures = "processor\t: 0\nBogoMIPS\t: 38.40\nCPU implementer\t: 0x51\n"
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", noFeatures))
    }

    @Test
    fun `an empty Features line fails open`() {
        // fp and asimd are architecturally mandatory on every ARMv8-A core and always appear
        // in elf_hwcap, so a real kernel never emits a Features line with nothing after it. An
        // empty token set means procfs was redacted or synthesised -- an unfamiliar format,
        // not an old CPU -- so this must fail open like any other unfamiliar format.
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo("Features\t:"))
        assertEquals(CpuSupport.Supported, result)
    }

    @Test
    fun `a whitespace-only Features line fails open`() {
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo("Features\t:   \t  "))
        assertEquals(CpuSupport.Supported, result)
    }

    @Test
    fun `fp16 and dotprod without atomics is supported`() {
        // LSE is implied by dotprod; requiring it would only add a way to reject a working device.
        val features = "Features\t: fp asimd aes pmull crc32 fphp asimdhp asimddp"
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", cpuinfo(features)))
    }

    @Test
    fun `isSupported reflects the variant`() {
        assertTrue(CpuSupport.Supported.isSupported)
        assertFalse(CpuSupport.Unsupported(listOf("asimddp")).isSupported)
    }

    @Test
    fun `unknown abi string fails open`() {
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("", cpuinfo(armv80Features)))
    }
}
