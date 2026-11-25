package dog.bald.arcade.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetplayEndpointResolverTest {

    @Test
    fun formatCandidateEndpoint_combinesPublicAndLan() {
        val result = NetplayEndpointResolver.formatCandidateEndpoint(
            publicEp = "186.156.23.107:7059",
            localIp = "192.168.0.3",
            port = 7059
        )
        assertEquals("186.156.23.107:7059|192.168.0.3:7059", result)
    }

    @Test
    fun formatCandidateEndpoint_handlesNullOrBlankLocalIp() {
        val result = NetplayEndpointResolver.formatCandidateEndpoint(
            publicEp = "186.156.23.107:7059",
            localIp = null,
            port = 7059
        )
        assertEquals("186.156.23.107:7059", result)
    }

    @Test
    fun formatCandidateEndpoint_alreadyContainsCandidate_doesNotDuplicate() {
        val result = NetplayEndpointResolver.formatCandidateEndpoint(
            publicEp = "186.156.23.107:7059|192.168.0.3:7059",
            localIp = "192.168.0.3",
            port = 7059
        )
        assertEquals("186.156.23.107:7059|192.168.0.3:7059", result)
    }

    @Test
    fun resolveEffectiveEndpoint_samePublicIp_selectsLanCandidate() {
        val peerCandidate = "186.156.23.107:7132|192.168.0.4:7132"
        val myPublicIp = "186.156.23.107:7059"

        val effective = NetplayEndpointResolver.resolveEffectiveEndpoint(peerCandidate, myPublicIp)
        assertEquals("192.168.0.4:7132", effective)
    }

    @Test
    fun resolveEffectiveEndpoint_differentPublicIp_selectsPublicCandidate() {
        val peerCandidate = "200.75.12.34:7132|192.168.1.10:7132"
        val myPublicIp = "186.156.23.107:7059"

        val effective = NetplayEndpointResolver.resolveEffectiveEndpoint(peerCandidate, myPublicIp)
        assertEquals("200.75.12.34:7132", effective)
    }

    @Test
    fun resolveEffectiveEndpoint_legacySingleEndpoint_returnsUnchanged() {
        val peerCandidate = "200.75.12.34:7132"
        val myPublicIp = "186.156.23.107:7059"

        val effective = NetplayEndpointResolver.resolveEffectiveEndpoint(peerCandidate, myPublicIp)
        assertEquals("200.75.12.34:7132", effective)
    }

    @Test
    fun isPrivateEndpoint_identifiesPrivateVsPublic() {
        assertTrue(NetplayEndpointResolver.isPrivateEndpoint("192.168.1.5:7000"))
        assertTrue(NetplayEndpointResolver.isPrivateEndpoint("10.0.0.1:7000"))
        assertTrue(NetplayEndpointResolver.isPrivateEndpoint("172.20.0.1:7000"))
        assertTrue(NetplayEndpointResolver.isPrivateEndpoint("127.0.0.1:7000"))

        assertFalse(NetplayEndpointResolver.isPrivateEndpoint("186.156.23.107:7059"))
        assertFalse(NetplayEndpointResolver.isPrivateEndpoint("186.156.23.107:7059|192.168.0.3:7059"))
    }
}
