package com.gialong.relayforge.runtime.worker;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationAddressPolicyTests {

    private final DestinationAddressPolicy policy = new DestinationAddressPolicy();

    @Test
    void acceptsPublicAddressesAndRejectsPrivateSpecialAndDocumentationRanges() throws Exception {
        assertThat(policy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(policy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();

        for (String prohibited : List.of(
                "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
                "192.0.2.1", "192.168.0.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "240.0.0.1",
                "::", "::1", "::ffff:10.1.2.3", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1"
        )) {
            assertThat(policy.isPublic(InetAddress.getByName(prohibited))).as(prohibited).isFalse();
        }
    }

    @Test
    void normalizesIpv4CompatibleIpv6BeforeApplyingTheIpv4PrivateAddressPolicy() throws Exception {
        byte[] compatiblePrivate = new byte[16];
        compatiblePrivate[12] = 10;
        compatiblePrivate[13] = 1;
        compatiblePrivate[14] = 2;
        compatiblePrivate[15] = 3;
        Inet6Address compatibleAddress = Inet6Address.getByAddress(null, compatiblePrivate, -1);
        Arrays.fill(compatiblePrivate, (byte) 0);

        assertThat(policy.isPublic(compatibleAddress)).isFalse();
    }

    @Test
    void keepsIpv6UnspecifiedAndLoopbackAddressesInTheirNativeForm() throws Exception {
        assertThat(policy.isLoopback(InetAddress.getByName("::"))).isFalse();
        assertThat(policy.isLoopback(InetAddress.getByName("::1"))).isTrue();
    }
}
