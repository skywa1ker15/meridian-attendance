package za.co.meridiangroup.attendance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phone normalisation, the password checklist and the invitation / reset link parser. */
class ActivationValidationTest {

    // ---- phone numbers (mirror backend/app/core/validation.py) ----

    @Test
    fun normalisesEveryShapeSouthAfricansType() {
        listOf(
            "0821234567", "082 123 4567", "082-123-4567", "(082) 123 4567", "082.123.4567",
            "+27821234567", "+27 82 123 4567", "0027821234567", "27821234567", " 082 123 4567 ",
            "082–123–4567",
        ).forEach { assertEquals("+27821234567", PhoneNumbers.normalise(it), "for input <$it>") }
    }

    @Test
    fun rejectsRubbish() {
        listOf("", "   ", "12345", "0821234", "08212345678", "0021234567", "+44 20 7946 0000", "+2782123456",
            "09212345678", "not a number", "0912345678", "0021234567890").forEach {
            assertNull(PhoneNumbers.normalise(it), "should not normalise <$it>")
        }
    }

    @Test
    fun emptyIsOnlyAnErrorWhenRequired() {
        assertNull(PhoneNumbers.error("", required = false))
        assertNotNull(PhoneNumbers.error("", required = true))
        assertNotNull(PhoneNumbers.error("123", required = false))
        assertNull(PhoneNumbers.error("082 123 4567", required = true))
    }

    @Test
    fun displaysNumbersTheLocalWay() {
        assertEquals("082 123 4567", PhoneNumbers.display("+27821234567"))
        assertEquals("031 123 4567", PhoneNumbers.display("0311234567"))
    }

    // ---- password policy (mirror backend/app/core/security.py) ----

    @Test
    fun checklistFollowsTheServerRules() {
        val ok = PasswordPolicy.check("Thembi-Rides-2026", listOf("MG-0042", "t.ndlovu@meridiangroup.co.za"), "Thembi-Rides-2026")
        assertTrue(ok.all { it.satisfied }, "expected a clean checklist, got $ok")

        val short = PasswordPolicy.check("Short-1", confirm = "Short-1")
        assertFalse(short.single { it.rule == PasswordPolicy.Rule.LENGTH }.satisfied)

        val withId = PasswordPolicy.check("xx-MG-0042-xx-yy", listOf("MG-0042"), "xx-MG-0042-xx-yy")
        assertFalse(withId.single { it.rule == PasswordPolicy.Rule.NOT_IDENTIFIER }.satisfied)

        val common = PasswordPolicy.check("Meridian2026", confirm = "Meridian2026")
        assertFalse(common.single { it.rule == PasswordPolicy.Rule.NOT_COMMON }.satisfied)
        assertFalse(PasswordPolicy.isAcceptable("aaaaaaaaaaaa"))

        val mismatch = PasswordPolicy.check("Thembi-Rides-2026", confirm = "Thembi-Rides-202")
        assertFalse(mismatch.single { it.rule == PasswordPolicy.Rule.MATCH }.satisfied)
        assertEquals(3, PasswordPolicy.check("Thembi-Rides-2026").size, "no confirm field means no match rule")
    }

    @Test
    fun mapsServerFieldCodes() {
        assertTrue(PasswordPolicy.messageForCode("too_short").contains("${PasswordPolicy.MIN_LENGTH}"))
        assertTrue(PasswordPolicy.messageForCode("too_common").isNotBlank())
        assertEquals("That one is no good", PasswordPolicy.messageForCode("That one is no good"))
    }

    // ---- invitation / reset links ----

    @Test
    fun parsesEmailedHttpsLinksAndDerivesTheServer() {
        val invite = assertNotNull(AppLinks.parse("https://attendance.meridiangroup.co.za/app/invite?token=abc123"))
        assertEquals(AppLinkKind.INVITE, invite.kind)
        assertEquals("abc123", invite.token)
        assertEquals("https://attendance.meridiangroup.co.za/", invite.serverRoot)

        val reset = assertNotNull(AppLinks.parse("https://host.example.co.za:8443/meridian/app/reset?token=t%2Fk&x=1"))
        assertEquals(AppLinkKind.RESET, reset.kind)
        assertEquals("t/k", reset.token)
        assertEquals("https://host.example.co.za:8443/meridian/", reset.serverRoot)
    }

    @Test
    fun parsesTheCustomSchemeFallbackWithoutAServer() {
        val link = assertNotNull(AppLinks.parse("meridian://invite?token=abc123"))
        assertEquals(AppLinkKind.INVITE, link.kind)
        assertNull(link.serverRoot)
        assertEquals(AppLinkKind.RESET, assertNotNull(AppLinks.parse("meridian://reset?token=abc")).kind)
        assertEquals(AppLinkKind.INVITE, assertNotNull(AppLinks.parse("meridianattendance://invite?token=abc")).kind)
    }

    @Test
    fun cleartextServersOnlyComeFromBuildsThatAllowThem() {
        assertNull(assertNotNull(AppLinks.parse("http://192.168.1.50:8000/app/invite?token=t")).serverRoot)
        assertEquals(
            "http://192.168.1.50:8000/",
            assertNotNull(AppLinks.parse("http://192.168.1.50:8000/app/invite?token=t", allowCleartext = true)).serverRoot,
        )
    }

    @Test
    fun ignoresEverythingElse() {
        listOf(
            null, "", "https://attendance.meridiangroup.co.za/app/invite", "https://attendance.meridiangroup.co.za/?token=t",
            "meridian://home?token=t", "meridianattendance://confirm/evt-1", "ftp://x.co.za/app/invite?token=t",
            "https://attendance.meridiangroup.co.za/app/invite?token=",
        ).forEach { assertNull(AppLinks.parse(it), "should not parse <$it>") }
    }

    @Test
    fun buildsTheLinksTheEmailAndWebPageUse() {
        assertEquals(
            "https://attendance.meridiangroup.co.za/app/invite?token=t1",
            AppLinks.webUrl("https://attendance.meridiangroup.co.za/", AppLinkKind.INVITE, "t1"),
        )
        assertEquals("meridian://reset?token=t1", AppLinks.appUrl(AppLinkKind.RESET, "t1"))
        assertIs<AppLink>(assertNotNull(AppLinks.parse(AppLinks.appUrl(AppLinkKind.RESET, "t1"))))
    }
}
