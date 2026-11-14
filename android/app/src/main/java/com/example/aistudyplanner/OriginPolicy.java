package com.example.aistudyplanner;
import java.net.URI;
import java.util.Locale;
/** Never interpret an incoming Intent URL as the configured server. */
public final class OriginPolicy {
    private OriginPolicy() {}
    public static String approvedOrigin(String input) {
        if(input==null || input.trim().isEmpty())return null;
        try {
            URI uri=URI.create(input.trim());String host=uri.getHost();
            if(!"https".equals(uri.getScheme()) || host==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)return null;
            host=host.toLowerCase(Locale.ROOT);
            if(!host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || !host.contains(".") || host.matches("[0-9.]+") || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".invalid"))return null;
            if(uri.getPort()!=-1 && uri.getPort()!=443)return null;
            if(!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))return null;
            return "https://"+host;
        } catch(IllegalArgumentException e){return null;}
    }
}
