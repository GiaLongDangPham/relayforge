package com.gialong.relayforge.runtime.worker;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Rejects non-public production targets, including mapped IPv4 and documentation space.
 */
final class DestinationAddressPolicy {

    boolean isPublic(InetAddress address) {
        InetAddress normalized = normalize(address);
        if (normalized.isAnyLocalAddress()
                || normalized.isLoopbackAddress()
                || normalized.isLinkLocalAddress()
                || normalized.isSiteLocalAddress()
                || normalized.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = normalized.getAddress();
        if (normalized instanceof Inet4Address) {
            return isPublicIpv4(bytes);
        }
        return isPublicIpv6(bytes);
    }

    boolean isLoopback(InetAddress address) {
        return normalize(address).isLoopbackAddress();
    }

    private static InetAddress normalize(InetAddress address) {
        if (address instanceof Inet6Address && hasEmbeddedIpv4(address.getAddress())) {
            try {
                return InetAddress.getByAddress(Arrays.copyOfRange(address.getAddress(), 12, 16));
            } catch (java.net.UnknownHostException exception) {
                throw new IllegalStateException("IPv4-mapped address could not be normalized", exception);
            }
        }
        return address;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return false;
        }
        if (first == 192 && second == 168) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19 || second == 51)) {
            return false;
        }
        return first != 203 || second != 0 || third != 113;
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        if ((unsigned(bytes[0]) & 0xfe) == 0xfc) {
            return false;
        }
        if (unsigned(bytes[0]) == 0x20 && unsigned(bytes[1]) == 0x01 && unsigned(bytes[2]) == 0x0d
                && unsigned(bytes[3]) == 0xb8) {
            return false;
        }
        return !(unsigned(bytes[0]) == 0x20 && unsigned(bytes[1]) == 0x02);
    }

    private static boolean hasEmbeddedIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        if (bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
            return true;
        }
        if (bytes[10] != 0 || bytes[11] != 0) {
            return false;
        }
        return bytes[12] != 0 || bytes[13] != 0 || bytes[14] != 0 || Byte.toUnsignedInt(bytes[15]) > 1;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }
}
