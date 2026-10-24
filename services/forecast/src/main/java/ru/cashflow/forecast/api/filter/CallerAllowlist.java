package ru.cashflow.forecast.api.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import ru.cashflow.forecast.config.ForecastProperties;

public class CallerAllowlist {
    private final List<Network> networks;

    public CallerAllowlist(ForecastProperties properties) {
        networks = properties.allowedCallerCidrs().stream().map(this::network).toList();
    }

    public boolean isAllowed(String remoteAddress) {
        try {
            byte[] address = literalAddress(remoteAddress);
            return networks.stream().anyMatch(network -> network.contains(address));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Network network(String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Caller CIDR must contain a numeric address and prefix");
        }
        byte[] address = literalAddress(parts[0]);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid caller CIDR prefix");
        }
        if (prefix < 0 || prefix > address.length * 8) {
            throw new IllegalArgumentException("Invalid caller CIDR prefix");
        }
        return new Network(address, prefix);
    }

    private byte[] literalAddress(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Numeric IP address required; DNS and zone IDs are not supported");
        }
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid IPv4 address");
            }
            byte[] bytes = new byte[4];
            for (int index = 0; index < 4; index++) {
                if (!parts[index].matches("0|[1-9][0-9]{0,2}")) {
                    throw new IllegalArgumentException("Invalid IPv4 address");
                }
                int number = Integer.parseInt(parts[index]);
                if (number > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 address");
                }
                bytes[index] = (byte) number;
            }
            return bytes;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
    }

    private record Network(byte[] address, int prefix) {
        private boolean contains(byte[] peer) {
            if (peer.length != address.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (peer[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
