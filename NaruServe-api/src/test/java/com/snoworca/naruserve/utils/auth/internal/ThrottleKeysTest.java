package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** The IP part of the login and API-key throttle keys (SEC-FLOW-011). */
public class ThrottleKeysTest {

    // SEC-FLOW-011: an IPv4-mapped IPv6 address counts as its IPv4 address in every spelling (dotted, hex, expanded),
    // so hex-mapped clients do not all share the 0:0:0:0::/64 counter
    @Test
    public void SEC_FLOW_011_ipv4MappedAddressesCountAsIpv4InEverySpelling() {
        assertEquals("192.0.2.1", ThrottleKeys.ipKey("::ffff:c000:201"));
        assertEquals("192.0.2.1", ThrottleKeys.ipKey("::FFFF:C000:0201"));
        assertEquals("192.0.2.1", ThrottleKeys.ipKey("0:0:0:0:0:ffff:c000:201"));
        assertEquals("1.2.3.4", ThrottleKeys.ipKey("0:0:0:0:0:ffff:1.2.3.4"));
        assertEquals("1.2.3.4", ThrottleKeys.ipKey("[::ffff:102:304]"));
        assertEquals("203.0.113.5", ThrottleKeys.ipKey("::ffff:203.0.113.5"));
        assertEquals(ThrottleKeys.loginIp("192.0.2.1"), ThrottleKeys.loginIp("::ffff:c000:201"));
        assertFalse("two hex-mapped clients have two counters",
                ThrottleKeys.loginIp("::ffff:c000:201").equals(ThrottleKeys.loginIp("::ffff:c000:202")));
        assertFalse(ThrottleKeys.apiKeyIp("::ffff:c000:201").equals(ThrottleKeys.apiKeyIp("::ffff:c633:6401")));
        // not mapped: still the /64 of the address
        assertEquals("0:0:0:0::/64", ThrottleKeys.ipKey("::1"));
        assertEquals("0:0:0:0::/64", ThrottleKeys.ipKey("::fffe:c000:201"));
        assertEquals("2001:db8:0:0::/64", ThrottleKeys.ipKey("2001:db8::ffff:c000:201"));
        // an embedded dotted part that is not an IPv4 address stays as written
        assertEquals("0:0:0:0:0:ffff:1.2.3", ThrottleKeys.ipKey("0:0:0:0:0:ffff:1.2.3"));
        assertEquals("0:0:0:0:0:ffff:1.2.3.999", ThrottleKeys.ipKey("0:0:0:0:0:ffff:1.2.3.999"));
    }
}
